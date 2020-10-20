/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx;

import static org.eclipse.openvsx.util.UrlUtil.createApiUrl;

import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;

import com.google.common.base.Strings;

import org.eclipse.openvsx.entities.Extension;
import org.eclipse.openvsx.entities.ExtensionVersion;
import org.eclipse.openvsx.entities.FileResource;
import org.eclipse.openvsx.entities.Namespace;
import org.eclipse.openvsx.entities.PersistedLog;
import org.eclipse.openvsx.entities.UserData;
import org.eclipse.openvsx.json.NamespaceJson;
import org.eclipse.openvsx.json.ResultJson;
import org.eclipse.openvsx.repositories.RepositoryService;
import org.eclipse.openvsx.search.SearchService;
import org.eclipse.openvsx.storage.GoogleCloudStorageService;
import org.eclipse.openvsx.storage.StorageUtilService;
import org.eclipse.openvsx.util.ErrorResultException;
import org.eclipse.openvsx.util.SemanticVersion;
import org.eclipse.openvsx.util.TimeUtil;
import org.eclipse.openvsx.util.UrlUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AdminService {

    @Autowired
    RepositoryService repositories;

    @Autowired
    EntityManager entityManager;

    @Autowired
    UserService users;

    @Autowired
    ExtensionValidator validator;

    @Autowired
    LocalRegistryService local;

    @Autowired
    UpstreamRegistryService upstream;

    @Autowired
    SearchService search;

    @Autowired
    StorageUtilService storageUtil;

    @Autowired
    GoogleCloudStorageService googleStorage;

    @Transactional(rollbackOn = ErrorResultException.class)
    public ResultJson deleteExtension(String namespaceName, String extensionName, String version, UserData admin)
            throws ErrorResultException {
        if (Strings.isNullOrEmpty(version)) {
            var extension = repositories.findExtension(extensionName, namespaceName);
            if (extension == null) {
                throw new ErrorResultException("Extension not found: " + namespaceName + "." + extensionName, HttpStatus.NOT_FOUND);
            }
            return deleteExtension(extension, admin);
        } else {
            var extVersion = repositories.findVersion(version, extensionName, namespaceName);
            if (extVersion == null) {
                throw new ErrorResultException("Extension not found: " + namespaceName + "." + extensionName + " version " + version, HttpStatus.NOT_FOUND);
            }
            return deleteExtension(extVersion, admin);
        }
    }

    protected ResultJson deleteExtension(Extension extension, UserData admin) throws ErrorResultException {
        var namespace = extension.getNamespace();
        var bundledRefs = repositories.findBundledExtensionsReference(extension);
        if (!bundledRefs.isEmpty()) {
            throw new ErrorResultException("Extension " + namespace.getName() + "." + extension.getName()
                    + " is bundled by the following extension packs: "
                    + bundledRefs.stream()
                        .map(ev -> ev.getExtension().getNamespace().getName() + "." + ev.getExtension().getName() + "@" + ev.getVersion())
                        .collect(Collectors.joining(", ")));
        }
        var dependRefs = repositories.findDependenciesReference(extension);
        if (!dependRefs.isEmpty()) {
            throw new ErrorResultException("The following extensions have a dependency on " + namespace.getName() + "." + extension.getName() + ": "
                    + dependRefs.stream()
                        .map(ev -> ev.getExtension().getNamespace().getName() + "." + ev.getExtension().getName() + "@" + ev.getVersion())
                        .collect(Collectors.joining(", ")));
        }
        extension.setLatest(null);
        extension.setPreview(null);
        for (var extVersion : extension.getVersions()) {
            removeExtensionVersion(extVersion);
        }
        for (var review : repositories.findAllReviews(extension)) {
            entityManager.remove(review);
        }
        entityManager.remove(extension);
        search.removeSearchEntry(extension);

        var result = ResultJson.success("Deleted " + namespace.getName() + "." + extension.getName());
        logAdminAction(admin, result);
        return result;
    }

    protected ResultJson deleteExtension(ExtensionVersion extVersion, UserData admin) {
        var extension = extVersion.getExtension();
        if (extension.getVersions().size() == 1) {
            return deleteExtension(extension, admin);
        }
        removeExtensionVersion(extVersion);
        if (extVersion.equals(extension.getLatest())) {
            var versions = extension.getVersions();
            versions.remove(extVersion);
            extension.setLatest(getLatestVersion(versions, false));
            if (extension.getLatest() == null)
                extension.setLatest(getLatestVersion(versions, true));
        }
        if (extVersion.equals(extension.getPreview())) {
            var versions = extension.getVersions();
            versions.remove(extVersion);
            extension.setPreview(getLatestVersion(versions, true));
        }
    
        var result = ResultJson.success("Deleted " + extension.getNamespace().getName() + "." + extension.getName() + " version " + extVersion.getVersion());
        logAdminAction(admin, result);
        return result;
    }

    private void removeExtensionVersion(ExtensionVersion extVersion) {
        repositories.findFiles(extVersion).forEach(file -> {
            if (file.getStorageType().equals(FileResource.STORAGE_GOOGLE) && googleStorage.isEnabled()) {
                googleStorage.removeFile(file.getName(), extVersion);
            }
            entityManager.remove(file);
        });
        entityManager.remove(extVersion);
    }

    private ExtensionVersion getLatestVersion(Iterable<ExtensionVersion> versions, boolean preview) {
        ExtensionVersion latest = null;
        SemanticVersion latestSemver = null;
        for (var extVer : versions) {
            if (extVer.isPreview() == preview) {
                var semver = extVer.getSemanticVersion();
                if (latestSemver == null || latestSemver.compareTo(semver) < 0) {
                    latest = extVer;
                    latestSemver = semver;
                }
            }
        }
        return latest;
    }

    @Transactional(rollbackOn = ErrorResultException.class)
    public ResultJson editNamespaceMember(String namespaceName, String userName, String provider, String role, UserData admin)
            throws ErrorResultException {
        var namespace = repositories.findNamespace(namespaceName);
        if (namespace == null) {
            throw new ErrorResultException("Namespace not found: " + namespaceName);
        }
        if (Strings.isNullOrEmpty(provider)) {
            provider = "github";
        }
        var user = repositories.findUserByLoginName(provider, userName);
        if (user == null) {
            throw new ErrorResultException("User not found: " + provider + "/" + userName);
        }

        ResultJson result;
        if (role.equals("remove")) {
            result = users.removeNamespaceMember(namespace, user);
        } else {
            result = users.addNamespaceMember(namespace, user, role);
        }
        for (var extension : repositories.findExtensions(namespace)) {
            search.updateSearchEntry(extension);
        }
        logAdminAction(admin, result);
        return result;
    }

    @Transactional(rollbackOn = ErrorResultException.class)
    public ResultJson createNamespace(NamespaceJson json) {
        var namespaceIssue = validator.validateNamespace(json.name);
        if (namespaceIssue.isPresent()) {
            throw new ErrorResultException(namespaceIssue.get().toString());
        }
        var namespace = repositories.findNamespace(json.name);
        if (namespace != null) {
            throw new ErrorResultException("Namespace already exists: " + namespace.getName());
        }
        namespace = new Namespace();
        namespace.setName(json.name);
        entityManager.persist(namespace);
        return ResultJson.success("Created namespace " + namespace.getName());
    }

    public NamespaceJson getNamespace(String namespaceName) {
        var serverUrl = UrlUtil.getBaseUrl();
        NamespaceJson namespace = local.getNamespace(namespaceName);
        if (namespace == null) {
            throw new ErrorResultException("Namespace not found: " + namespaceName, HttpStatus.NOT_FOUND);
        }
        namespace.membersUrl = createApiUrl(serverUrl, "admin", namespace.name, "members");
        namespace.roleUrl = createApiUrl(serverUrl, "admin", namespace.name, "change-member");
        return namespace;
    }

    @Transactional
    public void logAdminAction(UserData admin, ResultJson result) {
        if (result.success != null) {
            var log = new PersistedLog();
            log.setUser(admin);
            log.setTimestamp(TimeUtil.getCurrentUTC());
            log.setMessage(result.success);
            entityManager.persist(log);
        }
    }

    public UserData checkAdminUser() {
        var user = users.findLoggedInUser();
        if (user == null || !UserData.ROLE_ADMIN.equals(user.getRole())) {
            throw new ErrorResultException("Administration role is required.", HttpStatus.FORBIDDEN);
        }
        return user;
    }

}
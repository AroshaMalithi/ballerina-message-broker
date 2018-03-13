/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package io.ballerina.messaging.broker.auth.authorization.authorizer.rdbms.resource.dao.impl;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import io.ballerina.messaging.broker.auth.authorization.authorizer.rdbms.resource.AuthResource;
import io.ballerina.messaging.broker.auth.authorization.authorizer.rdbms.resource.dao.AuthResourceDao;
import io.ballerina.messaging.broker.auth.exception.BrokerAuthNotFoundException;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class implements {@link AuthResourceDao} to provide functionality manage in memory auth resources.
 */
public class AuthResourceInMemoryDao implements AuthResourceDao {

    /**
     * In memory resource store for non durable resources like exchanges, queues in a table structure as,
     * 1. resource type
     * 2. resource name
     * 3. auth resource
     */
    private Table<String, String, AuthResource> inMemoryResourceMap = HashBasedTable.create();

    @Override
    public void persist(AuthResource authResource) {
        inMemoryResourceMap.put(authResource.getResourceType(), authResource.getResourceName(), authResource);
    }

    @Override
    public void update(AuthResource authResource) {
        inMemoryResourceMap.put(authResource.getResourceType(), authResource.getResourceName(), authResource);
    }

    @Override
    public boolean delete(String resourceType, String resource) {
        AuthResource removedItem = inMemoryResourceMap.remove(resourceType, resource);
        return removedItem != null;
    }

    @Override
    public AuthResource read(String resourceType, String resource) {
        return inMemoryResourceMap.get(resourceType, resource);
    }

    @Override
    public List<AuthResource> readAll(String resourceType, String ownerId) {
        Map<String, AuthResource> resourceMap = inMemoryResourceMap.row(resourceType);
        if (Objects.nonNull(resourceMap)) {
            Collection<AuthResource> authResources = resourceMap.values();
            return authResources.stream()
                                .filter(authResource -> authResource.getOwner().equals(ownerId))
                                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public List<AuthResource> readAll(String resourceType, String action, String ownerId, List<String> userGroups) {
        Map<String, AuthResource> resourceMap = inMemoryResourceMap.row(resourceType);
        if (Objects.nonNull(resourceMap)) {
            Collection<AuthResource> authResources = resourceMap.values();
            return authResources.stream()
                                .filter(authResource ->
                                                authResource.getOwner().equals(ownerId)
                                                        || checkActionAndGroups(authResource.getActionsUserGroupsMap(),
                                                                                action,
                                                                                userGroups))
                                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private boolean checkActionAndGroups(Map<String, Set<String>> actionUserGroupsMap,
                                         String action,
                                         List<String> userGroups) {
        if (Objects.nonNull(actionUserGroupsMap)) {
            Set<String> authorizedUserGroups = actionUserGroupsMap.get(action);
            return Objects.nonNull(authorizedUserGroups) && authorizedUserGroups.stream()
                                                                                .anyMatch(userGroups::contains);
        }
        return false;
    }

    @Override
    public boolean isExists(String resourceType, String resourceName) {
        return inMemoryResourceMap.contains(resourceType, resourceName);
    }

    @Override
    public void updateOwner(String resourceType, String resourceName, String newOwner)
            throws BrokerAuthNotFoundException {
        AuthResource authResource = inMemoryResourceMap.get(resourceName, resourceType);

        if (Objects.nonNull(authResource)) {
            authResource.setOwner(newOwner);
        } else {
            throw new BrokerAuthNotFoundException(
                    "No auth resource found for resource type (" + resourceType + ") and resource name (" + resourceName
                            + ")");
        }
    }

    @Override
    public void addGroup(String resourceType, String resourceName, String action, String group)
            throws BrokerAuthNotFoundException {
        AuthResource authResource = inMemoryResourceMap.get(resourceName, resourceType);

        if (Objects.nonNull(authResource)) {
            Set<String> userGroups = authResource.getActionsUserGroupsMap()
                                              .computeIfAbsent(action, key -> new HashSet<>());
            userGroups.add(group);
        } else {
            throw new BrokerAuthNotFoundException(
                    "No auth resource found for resource type (" + resourceType + ") and resource name (" + resourceName
                            + ")");
        }

    }

    @Override
    public void removeGroup(String resourceType, String resourceName, String action, String group)
            throws BrokerAuthNotFoundException {
        AuthResource authResource = inMemoryResourceMap.get(resourceName, resourceType);

        if (Objects.nonNull(authResource)) {
            Set<String> userGroups = authResource.getActionsUserGroupsMap()
                                                 .computeIfAbsent(action, key -> new HashSet<>());
            userGroups.remove(group);
        } else {
            throw new BrokerAuthNotFoundException(
                    "No auth resource found for resource type (" + resourceType + ") and resource name (" + resourceName
                            + ")");
        }
    }

}

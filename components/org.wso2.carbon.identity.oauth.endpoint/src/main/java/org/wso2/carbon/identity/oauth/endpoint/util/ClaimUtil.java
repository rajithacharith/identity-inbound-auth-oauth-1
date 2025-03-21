/*
 * Copyright (c) 2013, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth.endpoint.util;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.oltu.oauth2.common.error.OAuthError;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.base.IdentityException;
import org.wso2.carbon.identity.claim.metadata.mgt.ClaimMetadataHandler;
import org.wso2.carbon.identity.core.util.IdentityCoreConstants;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCache;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheEntry;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheKey;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth.dao.OAuthAppDO;
import org.wso2.carbon.identity.oauth.endpoint.user.impl.UserInfoEndpointConfig;
import org.wso2.carbon.identity.oauth.user.UserInfoClaimRetriever;
import org.wso2.carbon.identity.oauth.user.UserInfoEndpointException;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.dto.OAuth2TokenValidationResponseDTO;
import org.wso2.carbon.identity.oauth2.model.AccessTokenDO;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.identity.openidconnect.OIDCClaimUtil;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.apache.commons.collections.MapUtils.isEmpty;
import static org.apache.commons.collections.MapUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.wso2.carbon.identity.core.util.IdentityUtil.isTokenLoggable;

/**
 * Util class which contains claim related data.
 */
public class ClaimUtil {

    private static final String SP_DIALECT = "http://wso2.org/oidc/claim";
    private static final String ATTRIBUTE_SEPARATOR = FrameworkUtils.getMultiAttributeSeparator();
    private static final Log log = LogFactory.getLog(ClaimUtil.class);

    private ClaimUtil() {

    }

    public static Map<String, Object> getUserClaimsUsingTokenResponse(OAuth2TokenValidationResponseDTO tokenResponse)
            throws UserInfoEndpointException {

        Map<ClaimMapping, String> userAttributes = getUserAttributesFromCache(tokenResponse);
        Map<String, Object> userClaimsInOIDCDialect;
        if (isEmpty(userAttributes)) {
            if (log.isDebugEnabled()) {
                log.debug("User attributes not found in cache against the token. Retrieved claims from user store.");
            }
            userClaimsInOIDCDialect = getClaimsFromUserStore(tokenResponse);
        } else {
            UserInfoClaimRetriever retriever = UserInfoEndpointConfig.getInstance().getUserInfoClaimRetriever();
            userClaimsInOIDCDialect = retriever.getClaimsMap(userAttributes);
        }

        if (isEmpty(userClaimsInOIDCDialect)) {
            userClaimsInOIDCDialect = new HashMap<>();
        }

        return userClaimsInOIDCDialect;
    }

    public static Map<String, Object> getClaimsFromUserStore(OAuth2TokenValidationResponseDTO tokenResponse)
            throws UserInfoEndpointException {

        try {
            String userId;
            String userTenantDomain;
            UserRealm realm;
            List<String> claimURIList = new ArrayList<>();
            Map<String, Object> mappedAppClaims = new HashMap<>();
            String subjectClaimValue = null;

            try {
                AccessTokenDO accessTokenDO = OAuth2Util.getAccessTokenDOfromTokenIdentifier(
                        OAuth2Util.getAccessTokenIdentifier(tokenResponse));
                userId = accessTokenDO.getAuthzUser().getUserId();
                userTenantDomain = accessTokenDO.getAuthzUser().getTenantDomain();

                // If the authenticated user is a federated user and had not mapped to local users, no requirement to
                // retrieve claims from local userstore.
                if (!OAuthServerConfiguration.getInstance().isMapFederatedUsersToLocal()) {
                    AuthenticatedUser authenticatedUser = accessTokenDO.getAuthzUser();
                    if (isNotEmpty(authenticatedUser.getUserStoreDomain())) {
                        String userstoreDomain = authenticatedUser.getUserStoreDomain();
                        if (OAuth2Util.isFederatedUser(authenticatedUser)) {
                            return handleClaimsForFederatedUser(tokenResponse, mappedAppClaims, userstoreDomain);
                        }
                    }
                }

                Map<String, String> spToLocalClaimMappings;
                String clientId = getClientID(accessTokenDO);
                OAuthAppDO oAuthAppDO = OAuth2Util.getAppInformationByClientId(clientId);
                String spTenantDomain = OAuth2Util.getTenantDomainOfOauthApp(oAuthAppDO);

                ServiceProvider serviceProvider = OAuth2Util.getServiceProvider(clientId, spTenantDomain);
                ClaimMapping[] requestedLocalClaimMappings = serviceProvider.getClaimConfig().getClaimMappings();
                String subjectClaimURI = getSubjectClaimUri(serviceProvider, requestedLocalClaimMappings);

                if (subjectClaimURI != null) {
                    claimURIList.add(subjectClaimURI);
                }

                boolean isSubjectClaimInRequested = false;
                if (subjectClaimURI != null || ArrayUtils.isNotEmpty(requestedLocalClaimMappings)) {
                    if (requestedLocalClaimMappings != null) {
                        for (ClaimMapping claimMapping : requestedLocalClaimMappings) {
                            if (claimMapping.isRequested()) {
                                claimURIList.add(claimMapping.getLocalClaim().getClaimUri());
                                if (claimMapping.getLocalClaim().getClaimUri().equals(subjectClaimURI)) {
                                    isSubjectClaimInRequested = true;
                                }
                            }
                        }
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Requested number of local claims: " + claimURIList.size());
                    }

                    spToLocalClaimMappings = ClaimMetadataHandler.getInstance().getMappingsMapFromOtherDialectToCarbon
                            (SP_DIALECT, null, userTenantDomain, true);

                    realm = getUserRealm(null, userTenantDomain);
                    Map<String, String> userClaims = getUserClaimsFromUserStore(userId, realm, claimURIList);

                    if (isNotEmpty(userClaims)) {
                        for (Map.Entry<String, String> entry : userClaims.entrySet()) {
                            //set local2sp role mappings
                            if (IdentityUtil.getRoleGroupClaims().stream().anyMatch(roleGroupClaimURI ->
                                    roleGroupClaimURI.equals(entry.getKey()))) {
                                String claimSeparator = getMultiAttributeSeparator(userId, realm);
                                entry.setValue(getSpMappedRoleClaim(serviceProvider, entry, claimSeparator));
                            }

                            String oidcClaimUri = spToLocalClaimMappings.get(entry.getKey());
                            String claimValue = entry.getValue();
                            if (oidcClaimUri != null) {
                                if (entry.getKey().equals(subjectClaimURI)) {
                                    subjectClaimValue = claimValue;
                                    if (!isSubjectClaimInRequested) {
                                        if (log.isDebugEnabled()) {
                                            log.debug("Subject claim: " + entry.getKey() + " is not a requested " +
                                                    "claim. Not adding to claim map.");
                                        }
                                        continue;
                                    }
                                }

                                if (isMultiValuedAttribute(claimValue)) {
                                    String[] attributeValues = processMultiValuedAttribute(claimValue);
                                    mappedAppClaims.put(oidcClaimUri, attributeValues);
                                } else {
                                    mappedAppClaims.put(oidcClaimUri, claimValue);
                                }

                                if (log.isDebugEnabled() &&
                                        isTokenLoggable(IdentityConstants.IdentityTokens.USER_CLAIMS)) {
                                    log.debug("Mapped claim: key -  " + oidcClaimUri + " value -" + claimValue);
                                }
                            }
                        }
                    }
                }

                if (StringUtils.isBlank(subjectClaimValue)) {
                    if (log.isDebugEnabled()) {
                        log.debug("No subject claim found. Defaulting to username as the sub claim.");
                    }
                    subjectClaimValue = getUsernameFromTokenResponse(tokenResponse);
                }

                if (log.isDebugEnabled() && isTokenLoggable(IdentityConstants.IdentityTokens.USER_CLAIMS)) {
                    log.debug("Subject claim(sub) value: " + subjectClaimValue + " set in returned claims.");
                }
                mappedAppClaims.put(OAuth2Util.SUB, subjectClaimValue);
            } catch (InvalidOAuthClientException e) {
                if (log.isDebugEnabled()) {
                    log.debug(" Error while retrieving App information with provided client id.", e);
                }
                throw new IdentityOAuth2Exception(e.getMessage());
            } catch (Exception e) {
                String authorizedUserName = tokenResponse.getAuthorizedUser();
                if (e instanceof UserStoreException) {
                    if (e.getMessage().contains("UserNotFound")) {
                        if (log.isDebugEnabled()) {
                            log.debug(StringUtils.isNotEmpty(authorizedUserName) ? "User with username: "
                                    + authorizedUserName + ", cannot be found in user store" : "User cannot " +
                                    "found in user store");
                        }
                    }
                } else {
                    String errMsg = StringUtils.isNotEmpty(authorizedUserName) ? "Error while retrieving the claims " +
                            "from user store for the username: " + authorizedUserName : "Error while retrieving the " +
                            "claims from user store";
                    log.error(errMsg, e);
                    throw new IdentityOAuth2Exception(errMsg);
                }
            }
            return mappedAppClaims;
        } catch (IdentityOAuth2Exception e) {
            throw new UserInfoEndpointException("Error while retrieving claims for user: " +
                    tokenResponse.getAuthorizedUser(), e);
        }
    }

    /**
     * Map the local roles of a user to service provider mapped role values.
     *
     * @param serviceProvider
     * @param locallyMappedUserRoles
     * @deprecated use {@link OIDCClaimUtil#getServiceProviderMappedUserRoles(ServiceProvider, List, String)} instead.
     */
    @Deprecated
    public static String getServiceProviderMappedUserRoles(ServiceProvider serviceProvider,
                                                           List<String> locallyMappedUserRoles,
                                                           String claimSeparator) throws FrameworkException {

        return OIDCClaimUtil.getServiceProviderMappedUserRoles(serviceProvider, locallyMappedUserRoles, claimSeparator);
    }

    private static String getSpMappedRoleClaim(ServiceProvider serviceProvider,
                                               Map.Entry<String, String> entry,
                                               String claimSeparator) throws FrameworkException {

        String roleClaim = entry.getValue();
        List<String> rolesList = Arrays.asList(roleClaim.split(claimSeparator));
        return OIDCClaimUtil.getServiceProviderMappedUserRoles(serviceProvider, rolesList, claimSeparator);
    }

    private static String getMultiAttributeSeparator(String username,
                                                     UserRealm realm) throws UserStoreException {

        String domain = IdentityUtil.extractDomainFromName(username);
        RealmConfiguration realmConfiguration =
                realm.getUserStoreManager().getSecondaryUserStoreManager(domain).getRealmConfiguration();
        String claimSeparator =
                realmConfiguration.getUserStoreProperty(IdentityCoreConstants.MULTI_ATTRIBUTE_SEPARATOR);
        if (StringUtils.isBlank(claimSeparator)) {
            claimSeparator = IdentityCoreConstants.MULTI_ATTRIBUTE_SEPARATOR_DEFAULT;
        }
        return claimSeparator;
    }

    private static Map<String, String> getUserClaimsFromUserStore(String userId,
                                                                  UserRealm realm,
                                                                  List<String> claimURIList) throws UserStoreException {

        AbstractUserStoreManager userstore = (AbstractUserStoreManager) realm.getUserStoreManager();
        if (userstore == null) {
            throw new UserStoreException("Unable to retrieve UserStoreManager");
        }
        Map<String, String> userClaims =
                userstore.getUserClaimValuesWithID(userId, claimURIList.toArray(new String[0]), null);
        if (log.isDebugEnabled()) {
            log.debug("User claims retrieved from user store: " + userClaims.size());
        }
        return userClaims;
    }

    private static UserRealm getUserRealm(String username,
                                          String userTenantDomain) throws IdentityException, UserInfoEndpointException {

        UserRealm realm;
        realm = IdentityTenantUtil.getRealm(userTenantDomain, username);
        if (realm == null) {
            throw new UserInfoEndpointException("Invalid User Domain provided: " + userTenantDomain +
                    "Cannot retrieve user claims for user: " + username);
        }
        return realm;
    }

    private static String getSubjectClaimUri(ServiceProvider serviceProvider, ClaimMapping[] requestedLocalClaimMap) {

        String subjectClaimURI = serviceProvider.getLocalAndOutBoundAuthenticationConfig().getSubjectClaimUri();
        if (requestedLocalClaimMap != null) {
            for (ClaimMapping claimMapping : requestedLocalClaimMap) {
                if (claimMapping.getRemoteClaim().getClaimUri().equals(subjectClaimURI)) {
                    subjectClaimURI = claimMapping.getLocalClaim().getClaimUri();
                    break;
                }
            }
        }
        return subjectClaimURI;
    }

    private static String getClientID(AccessTokenDO accessTokenDO) throws UserInfoEndpointException {

        if (accessTokenDO != null) {
            return accessTokenDO.getConsumerKey();
        } else {
            // this means the token is not active so we can't proceed further
            throw new UserInfoEndpointException(OAuthError.ResourceResponse.INVALID_TOKEN,
                    "Invalid Access Token. Access token is not ACTIVE.");
        }
    }

    private static Map<String, Object> handleClaimsForFederatedUser(OAuth2TokenValidationResponseDTO tokenResponse,
                                                                    Map<String, Object> mappedAppClaims,
                                                                    String userStoreDomain) {

        if (log.isDebugEnabled()) {
            log.debug("Federated user store prefix available in domain " + userStoreDomain + ". User is federated so " +
                    "not retrieving claims from user store.");
        }
        // Add the sub claim.
        String subjectClaimValue = tokenResponse.getAuthorizedUser();
        mappedAppClaims.put(OAuth2Util.SUB, tokenResponse.getAuthorizedUser());
        if (log.isDebugEnabled() && isTokenLoggable(IdentityConstants.IdentityTokens.USER_CLAIMS)) {
            log.debug("Subject claim(sub) value: " + subjectClaimValue + " set in returned claims.");
        }
        return mappedAppClaims;
    }

    private static String getUsernameFromTokenResponse(OAuth2TokenValidationResponseDTO tokenResponse) {

        String tenantAwareUsername = MultitenantUtils.getTenantAwareUsername(tokenResponse.getAuthorizedUser());
        return UserCoreUtil.removeDomainFromName(tenantAwareUsername);
    }

    private static Map<ClaimMapping, String> getUserAttributesFromCache(OAuth2TokenValidationResponseDTO tokenResponse)
            throws UserInfoEndpointException {

        AuthorizationGrantCacheKey cacheKey =
                new AuthorizationGrantCacheKey(OAuth2Util.getAccessTokenIdentifier(tokenResponse));
        AuthorizationGrantCacheEntry cacheEntry =
                AuthorizationGrantCache.getInstance().getValueFromCacheByToken(cacheKey);
        if (cacheEntry == null) {
            return new HashMap<>();
        }
        return cacheEntry.getUserAttributes();
    }

    /**
     * Check whether claim value is multivalued attribute or not by using attribute separator.
     *
     * @param claimValue String value contains claims.
     * @return Whether it is multivalued attribute or not.
     */
    public static boolean isMultiValuedAttribute(String claimValue) {

        return StringUtils.contains(claimValue, ATTRIBUTE_SEPARATOR);
    }

    /**
     * Split multivalued attribute string value by attribute separator.
     *
     * @param claimValue String value contains claims.
     * @return String array of multivalued claim values.
     */
    public static String[] processMultiValuedAttribute(String claimValue) {

        return claimValue.split(Pattern.quote(ATTRIBUTE_SEPARATOR));
    }
}

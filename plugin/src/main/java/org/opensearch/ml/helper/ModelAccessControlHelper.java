/*
 *
 *  * Copyright OpenSearch Contributors
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.opensearch.ml.helper;

import static org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_GROUP_INDEX;
import static org.opensearch.ml.settings.MLCommonsSettings.ML_COMMONS_MODEL_ACCESS_CONTROL_ENABLED;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;

import org.apache.lucene.search.join.ScoreMode;
import org.opensearch.action.ActionListener;
import org.opensearch.action.get.GetRequest;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.CollectionUtils;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.ExistsQueryBuilder;
import org.opensearch.index.query.IdsQueryBuilder;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.MatchPhraseQueryBuilder;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.index.query.NestedQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.RangeQueryBuilder;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.index.query.TermsQueryBuilder;
import org.opensearch.ml.common.MLModelGroup;
import org.opensearch.ml.common.ModelAccessIdentifier;
import org.opensearch.ml.common.exception.MLResourceNotFoundException;
import org.opensearch.ml.common.exception.MLValidationException;
import org.opensearch.ml.utils.MLNodeUtils;
import org.opensearch.search.builder.SearchSourceBuilder;

import com.google.common.collect.ImmutableList;

@Log4j2
public class ModelAccessControlHelper {

    private volatile boolean modelAccessControlEnabled;

    public ModelAccessControlHelper(ClusterService clusterService, Settings settings) {
        modelAccessControlEnabled = ML_COMMONS_MODEL_ACCESS_CONTROL_ENABLED.get(settings);
        clusterService
            .getClusterSettings()
            .addSettingsUpdateConsumer(ML_COMMONS_MODEL_ACCESS_CONTROL_ENABLED, it -> modelAccessControlEnabled = it);
    }

    private static final List<Class<?>> SUPPORTED_QUERY_TYPES = ImmutableList
        .of(
            IdsQueryBuilder.class,
            MatchQueryBuilder.class,
            MatchAllQueryBuilder.class,
            MatchPhraseQueryBuilder.class,
            TermQueryBuilder.class,
            TermsQueryBuilder.class,
            ExistsQueryBuilder.class,
            RangeQueryBuilder.class
        );

    public void validateModelGroupAccess(User user, String modelGroupId, Client client, ActionListener<Boolean> listener) {
        if (modelGroupId == null || isAdmin(user) || !isSecurityEnabledAndModelAccessControlEnabled(user)) {
            listener.onResponse(true);
            return;
        }

        List<String> userBackendRoles = user.getBackendRoles();
        GetRequest getModelGroupRequest = new GetRequest(ML_MODEL_GROUP_INDEX).id(modelGroupId);
        try {
            client.get(getModelGroupRequest, ActionListener.wrap(r -> {
                if (r != null && r.isExists()) {
                    try (
                        XContentParser parser = MLNodeUtils
                            .createXContentParserFromRegistry(NamedXContentRegistry.EMPTY, r.getSourceAsBytesRef())
                    ) {
                        ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
                        MLModelGroup mlModelGroup = MLModelGroup.parse(parser);
                        ModelAccessIdentifier modelAccessIdentifier = ModelAccessIdentifier.from(mlModelGroup.getAccess());
                        if (mlModelGroup.getOwner() == null) {
                            // previous security plugin not enabled, model defaults to public.
                            listener.onResponse(true);
                        } else if (ModelAccessIdentifier.RESTRICTED == modelAccessIdentifier) {
                            if (mlModelGroup.getBackendRoles() == null || mlModelGroup.getBackendRoles().size() == 0) {
                                throw new IllegalStateException("Backend roles shouldn't be null");
                            } else {
                                listener
                                    .onResponse(
                                        Optional
                                            .ofNullable(userBackendRoles)
                                            .orElse(ImmutableList.of())
                                            .stream()
                                            .anyMatch(mlModelGroup.getBackendRoles()::contains)
                                    );
                            }
                        } else if (ModelAccessIdentifier.PUBLIC == modelAccessIdentifier) {
                            listener.onResponse(true);
                        } else if (ModelAccessIdentifier.PRIVATE == modelAccessIdentifier) {
                            if (isOwner(mlModelGroup.getOwner(), user))
                                listener.onResponse(true);
                            else
                                listener.onResponse(false);
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse ml model group");
                        listener.onFailure(e);
                    }
                } else {
                    listener.onFailure(new MLResourceNotFoundException("Fail to find model group"));
                }
            }, e -> {
                log.error("Failed to validate Access", e);
                listener.onFailure(new MLValidationException("Failed to validate Access"));
            }));
        } catch (Exception e) {
            log.error("Failed to validate Access", e);
            listener.onFailure(e);
        }
    }

    public boolean skipModelAccessControl(User user) {
        return user == null || !modelAccessControlEnabled || isAdmin(user);
    }

    public boolean isSecurityEnabledAndModelAccessControlEnabled(User user) {
        return user != null && modelAccessControlEnabled;
    }

    public boolean isAdmin(User user) {
        if (user == null) {
            return false;
        }
        if (CollectionUtils.isEmpty(user.getRoles())) {
            return false;
        }
        return user.getRoles().contains("all_access");
    }

    public boolean isOwner(User owner, User user) {
        if (user == null || owner == null) {
            return false;
        }
        return owner.getName().equals(user.getName());
    }

    public boolean isOwnerStillHasPermission(User user, MLModelGroup mlModelGroup) {
        // when security plugin is disabled, or model access control not enabled, the model is a public model and anyone has permission to
        // it.
        if (!isSecurityEnabledAndModelAccessControlEnabled(user))
            return true;
        ModelAccessIdentifier access = ModelAccessIdentifier.from(mlModelGroup.getAccess());
        if (ModelAccessIdentifier.PUBLIC == access) {
            return true;
        } else if (ModelAccessIdentifier.PRIVATE == access) {
            return isOwner(user, mlModelGroup.getOwner());
        } else if (ModelAccessIdentifier.RESTRICTED == access) {
            if (CollectionUtils.isEmpty(mlModelGroup.getBackendRoles())) {
                throw new IllegalStateException("Backend roles should not be null");
            }
            return user.getBackendRoles() != null && new HashSet<>(mlModelGroup.getBackendRoles()).containsAll(user.getBackendRoles());
        }
        throw new IllegalStateException("Access shouldn't be null");
    }

    public SearchSourceBuilder addUserBackendRolesFilter(User user, SearchSourceBuilder searchSourceBuilder) {
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.should(QueryBuilders.termQuery(MLModelGroup.ACCESS, ModelAccessIdentifier.PUBLIC.getValue()));
        boolQueryBuilder.should(QueryBuilders.termsQuery(MLModelGroup.BACKEND_ROLES_FIELD + ".keyword", user.getBackendRoles()));

        BoolQueryBuilder privateBoolQuery = new BoolQueryBuilder();
        String ownerName = "owner.name.keyword";
        TermQueryBuilder ownerNameTermQuery = QueryBuilders.termQuery(ownerName, user.getName());
        NestedQueryBuilder nestedQueryBuilder = new NestedQueryBuilder(MLModelGroup.OWNER, ownerNameTermQuery, ScoreMode.None);
        privateBoolQuery.must(nestedQueryBuilder);
        privateBoolQuery.must(QueryBuilders.termQuery(MLModelGroup.ACCESS, ModelAccessIdentifier.PRIVATE.getValue()));
        boolQueryBuilder.should(privateBoolQuery);
        QueryBuilder query = searchSourceBuilder.query();
        if (query == null) {
            searchSourceBuilder.query(boolQueryBuilder);
        } else if (query instanceof BoolQueryBuilder) {
            ((BoolQueryBuilder) query).filter(boolQueryBuilder);
        } else if (isSupportedQueryType(query.getClass())) {
            BoolQueryBuilder rewriteQuery = new BoolQueryBuilder();
            rewriteQuery.must(query);
            rewriteQuery.filter(boolQueryBuilder);
            searchSourceBuilder.query(rewriteQuery);
        } else {
            throw new MLValidationException(
                "Search API only supports [bool, ids, match, match_all, term, terms, exists, range] query type"
            );
        }
        return searchSourceBuilder;
    }

    public SearchSourceBuilder createSearchSourceBuilder(User user) {
        return addUserBackendRolesFilter(user, new SearchSourceBuilder());
    }

    public boolean isSupportedQueryType(Class<?> queryType) {
        return SUPPORTED_QUERY_TYPES.stream().anyMatch(x -> x.isAssignableFrom(queryType));
    }
}
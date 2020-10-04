package com.appsmith.server.services;

import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionExecutionResult;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.PaginationField;
import com.appsmith.external.models.PaginationType;
import com.appsmith.external.models.Param;
import com.appsmith.external.models.Policy;
import com.appsmith.external.models.Property;
import com.appsmith.external.models.Provider;
import com.appsmith.external.pluginExceptions.AppsmithPluginError;
import com.appsmith.external.pluginExceptions.AppsmithPluginException;
import com.appsmith.external.pluginExceptions.StaleConnectionException;
import com.appsmith.external.plugins.PluginExecutor;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.acl.PolicyGenerator;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Action;
import com.appsmith.server.domains.ActionProvider;
import com.appsmith.server.domains.Datasource;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.Page;
import com.appsmith.server.domains.Plugin;
import com.appsmith.server.domains.PluginType;
import com.appsmith.server.dtos.ActionDTO;
import com.appsmith.server.dtos.ExecuteActionDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.MustacheHelper;
import com.appsmith.server.helpers.PluginExecutorHelper;
import com.appsmith.server.repositories.NewActionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.ArrayUtils;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import javax.lang.model.SourceVersion;
import javax.validation.Validator;
import javax.validation.constraints.NotNull;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.appsmith.server.acl.AclPermission.EXECUTE_ACTIONS;
import static com.appsmith.server.acl.AclPermission.EXECUTE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.MANAGE_ACTIONS;
import static com.appsmith.server.acl.AclPermission.MANAGE_DATASOURCES;
import static com.appsmith.server.acl.AclPermission.READ_PAGES;
import static com.appsmith.server.helpers.BeanCopyUtils.copyNewFieldValuesIntoOldObject;
import static java.lang.Boolean.TRUE;

@Service
@Slf4j
public class NewActionServiceImpl extends BaseService<NewActionRepository, NewAction, String> implements NewActionService {

    private final NewActionRepository repository;
    private final DatasourceService datasourceService;
    private final PluginService pluginService;
    private final ObjectMapper objectMapper;
    private final DatasourceContextService datasourceContextService;
    private final PluginExecutorHelper pluginExecutorHelper;
    private final SessionUserService sessionUserService;
    private final MarketplaceService marketplaceService;
    private final PolicyGenerator policyGenerator;
    private final NewPageService newPageService;

    public NewActionServiceImpl(Scheduler scheduler,
                                Validator validator,
                                MongoConverter mongoConverter,
                                ReactiveMongoTemplate reactiveMongoTemplate,
                                NewActionRepository repository,
                                AnalyticsService analyticsService,
                                DatasourceService datasourceService,
                                PluginService pluginService,
                                ObjectMapper objectMapper,
                                DatasourceContextService datasourceContextService,
                                PluginExecutorHelper pluginExecutorHelper,
                                SessionUserService sessionUserService,
                                MarketplaceService marketplaceService,
                                PolicyGenerator policyGenerator,
                                NewPageService newPageService) {
        super(scheduler, validator, mongoConverter, reactiveMongoTemplate, repository, analyticsService);
        this.repository = repository;
        this.datasourceService = datasourceService;
        this.pluginService = pluginService;
        this.objectMapper = objectMapper;
        this.datasourceContextService = datasourceContextService;
        this.pluginExecutorHelper = pluginExecutorHelper;
        this.sessionUserService = sessionUserService;
        this.marketplaceService = marketplaceService;
        this.policyGenerator = policyGenerator;
        this.newPageService = newPageService;
    }

    private Boolean validateActionName(String name) {
        boolean isValidName = SourceVersion.isName(name);
        String pattern = "^((?=[A-Za-z0-9_])(?![\\\\-]).)*$";
        boolean doesPatternMatch = name.matches(pattern);
        return (isValidName && doesPatternMatch);
    }

    private ActionDTO generateDTOFromAction(Action action) {
        ActionDTO actionDTO = new ActionDTO();
        actionDTO.setName(action.getName());
        actionDTO.setDatasource(action.getDatasource());
        actionDTO.setPageId(action.getPageId());
        actionDTO.setActionConfiguration(action.getActionConfiguration());
        actionDTO.setExecuteOnLoad(action.getExecuteOnLoad());
        actionDTO.setDynamicBindingPathList(action.getDynamicBindingPathList());
        actionDTO.setIsValid(action.getIsValid());
        actionDTO.setInvalids(action.getInvalids());
        actionDTO.setJsonPathKeys(action.getJsonPathKeys());
        actionDTO.setCacheResponse(action.getCacheResponse());
        actionDTO.setUserSetOnLoad(action.getUserSetOnLoad());
        actionDTO.setConfirmBeforeExecute(action.getConfirmBeforeExecute());

        return actionDTO;
    }

    private Action createActionFromDTO(ActionDTO actionDTO) {
        Action action = new Action();
        action.setName(actionDTO.getName());
        action.setDatasource(actionDTO.getDatasource());
        action.setPageId(actionDTO.getPageId());
        action.setCollectionId(actionDTO.getCollectionId());
        action.setActionConfiguration(actionDTO.getActionConfiguration());
        action.setExecuteOnLoad(actionDTO.getExecuteOnLoad());
        action.setDynamicBindingPathList(actionDTO.getDynamicBindingPathList());
        action.setIsValid(actionDTO.getIsValid());
        action.setInvalids(actionDTO.getInvalids());
        action.setJsonPathKeys(actionDTO.getJsonPathKeys());
        action.setCacheResponse(actionDTO.getCacheResponse());
        action.setUserSetOnLoad(actionDTO.getUserSetOnLoad());
        action.setConfirmBeforeExecute(actionDTO.getConfirmBeforeExecute());

        return action;
    }

    private void setCommonFieldsFromNewActionIntoAction(NewAction newAction, Action action) {
        // Set the fields from NewAction into Action
        action.setOrganizationId(newAction.getOrganizationId());
        action.setPluginType(newAction.getPluginType());
        action.setPluginId(newAction.getPluginId());
        action.setTemplateId(newAction.getTemplateId());
        action.setProviderId(newAction.getProviderId());
        action.setDocumentation(newAction.getDocumentation());
    }

    private void setCommonFieldsFromActionIntoNewAction(Action action, NewAction newAction) {
        // Set the fields from NewAction into Action
        newAction.setOrganizationId(action.getOrganizationId());
        newAction.setPluginType(action.getPluginType());
        newAction.setPluginId(action.getPluginId());
        newAction.setTemplateId(action.getTemplateId());
        newAction.setProviderId(action.getProviderId());
        newAction.setDocumentation(action.getDocumentation());
    }

    private Mono<Action> getActionByViewMode(NewAction newAction, Boolean viewMode) {
        Action action = null;

        if (TRUE.equals(viewMode)) {
            if (newAction.getPublishedAction() != null) {
                action = createActionFromDTO(newAction.getPublishedAction());
            } else {
                // We are trying to fetch published action but it doesnt exist because the action hasn't been published yet
                return Mono.empty();
            }
        } else {
            if (newAction.getUnpublishedAction() != null) {
                action = createActionFromDTO(newAction.getUnpublishedAction());
            }
        }

        // Set the base domain fields
        action.setUserPermissions(newAction.getUserPermissions());
        action.setId(newAction.getId());
        action.setPolicies(newAction.getPolicies());

        // Set the fields from NewAction into Action
        setCommonFieldsFromNewActionIntoAction(newAction, action);

        return Mono.just(action);
    }

    private void generateAndSetActionPolicies(NewPage page, NewAction action) {
        Set<Policy> documentPolicies = policyGenerator.getAllChildPolicies(page.getPolicies(), Page.class, Action.class);
        action.setPolicies(documentPolicies);
    }

    @Override
    public Mono<Action> createAction(@NotNull Action action) {
        if (action.getId() != null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "id"));
        }

        if (action.getPageId() == null || action.getPageId().isBlank()) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.PAGE_ID));
        }

        NewAction newAction = new NewAction();

        return newPageService
                .findById(action.getPageId(), READ_PAGES)
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, "page", action.getPageId())))
                .flatMap(page -> {

                    // Set the applicationId
                    newAction.setApplicationId(page.getApplicationId());

                    // Inherit the action policies from the page.
                    generateAndSetActionPolicies(page, newAction);

                    // If the datasource is embedded, check for organizationId and set it in action
                    if (action.getDatasource() != null &&
                            action.getDatasource().getId() == null) {
                        Datasource datasource = action.getDatasource();
                        if (datasource.getOrganizationId() == null) {
                            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ORGANIZATION_ID));
                        }
                        action.setOrganizationId(datasource.getOrganizationId());
                    }

                    newAction.setUnpublishedAction(generateDTOFromAction(action));
                    setCommonFieldsFromActionIntoNewAction(action, newAction);

                    return Mono.just(newAction);
                })
                .flatMap(this::validateAndSaveActionToRepository);
    }

    private Mono<Action> validateAndSaveActionToRepository(NewAction newAction) {
        ActionDTO action = newAction.getUnpublishedAction();

        //Default the validity to true and invalids to be an empty set.
        Set<String> invalids = new HashSet<>();
        action.setIsValid(true);

        if (action.getName() == null || action.getName().trim().isEmpty()) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.NAME));
        }

        if (action.getPageId() == null || action.getPageId().isBlank()) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.PAGE_ID));
        }

        if (!validateActionName(action.getName())) {
            action.setIsValid(false);
            invalids.add(AppsmithError.INVALID_ACTION_NAME.getMessage());
        }

        if (action.getActionConfiguration() == null) {
            action.setIsValid(false);
            invalids.add(AppsmithError.NO_CONFIGURATION_FOUND_IN_ACTION.getMessage());
        }

        if (action.getDatasource() == null || action.getDatasource().getIsAutoGenerated()) {
            if (action.getPluginType() != PluginType.JS) {
                // This action isn't of type JS functions which requires that the pluginType be set by the client. Hence,
                // datasource is very much required for such an action.
                action.setIsValid(false);
                invalids.add(AppsmithError.DATASOURCE_NOT_GIVEN.getMessage());
            }
            action.setInvalids(invalids);
            return super.create(newAction)
                    .flatMap(savedAction -> getActionByViewMode(savedAction, false));
        }

        Mono<Datasource> datasourceMono;
        if (action.getDatasource().getId() == null) {
            datasourceMono = Mono.just(action.getDatasource())
                    .flatMap(datasourceService::validateDatasource);
        } else {
            //Data source already exists. Find the same.
            datasourceMono = datasourceService.findById(action.getDatasource().getId(), MANAGE_DATASOURCES)
                    .switchIfEmpty(Mono.defer(() -> {
                        action.setIsValid(false);
                        invalids.add(AppsmithError.NO_RESOURCE_FOUND.getMessage(FieldName.DATASOURCE, action.getDatasource().getId()));
                        return Mono.just(action.getDatasource());
                    }))
                    .map(datasource -> {
                        // datasource is found. Update the action.
                        newAction.setOrganizationId(datasource.getOrganizationId());
                        return datasource;
                    });
        }

        Mono<Plugin> pluginMono = datasourceMono.flatMap(datasource -> {
            if (datasource.getPluginId() == null) {
                return Mono.error(new AppsmithException(AppsmithError.PLUGIN_ID_NOT_GIVEN));
            }
            return pluginService.findById(datasource.getPluginId())
                    .switchIfEmpty(Mono.defer(() -> {
                        action.setIsValid(false);
                        invalids.add(AppsmithError.NO_RESOURCE_FOUND.getMessage(FieldName.PLUGIN, datasource.getPluginId()));
                        return Mono.just(new Plugin());
                    }));
        });

        return pluginMono
                .zipWith(datasourceMono)
                //Set plugin in the action before saving.
                .map(tuple -> {
                    Plugin plugin = tuple.getT1();
                    Datasource datasource = tuple.getT2();
                    action.setDatasource(datasource);
                    action.setInvalids(invalids);
                    newAction.setPluginType(plugin.getType());
                    newAction.setPluginId(plugin.getId());
                    return newAction;
                }).map(act -> extractAndSetJsonPathKeys(act))
                .flatMap(super::create)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.REPOSITORY_SAVE_FAILED)))
                .flatMap(this::setTransientFieldsInUnpublishedAction)
                .flatMap(savedAction -> getActionByViewMode(savedAction, false));
    }

    /**
     * This function extracts all the mustache template keys (as per the regex) and returns them to the calling fxn
     * This set of keys is stored separately in the field `jsonPathKeys` in the action object. The client
     * uses the set `jsonPathKeys` to simplify it's value substitution.
     *
     * @param actionConfiguration
     * @return
     */
    private Set<String> extractKeysFromAction(ActionConfiguration actionConfiguration) {
        if (actionConfiguration == null) {
            return new HashSet<>();
        }

        return MustacheHelper.extractMustacheKeysFromFields(actionConfiguration);
    }

    /**
     * This function extracts the mustache keys and sets them in the field jsonPathKeys in the action object
     *
     * @param newAction
     * @return
     */
    public NewAction extractAndSetJsonPathKeys(NewAction newAction) {
        ActionDTO action = newAction.getUnpublishedAction();
        Set<String> actionKeys = extractKeysFromAction(action.getActionConfiguration());
        Set<String> datasourceKeys = datasourceService.extractKeysFromDatasource(action.getDatasource());
        Set<String> keys = new HashSet<>() {{
            addAll(actionKeys);
            addAll(datasourceKeys);
        }};
        action.setJsonPathKeys(keys);

        return newAction;
    }

    private Mono<NewAction> setTransientFieldsInUnpublishedAction(NewAction newAction) {
        ActionDTO action = newAction.getUnpublishedAction();

        // In case of an action which was imported from a 3P API, fill in the extra information of the provider required by the front end UI.
        Mono<ActionDTO> providerUpdateMono;
        if ((action.getTemplateId() != null) && (action.getProviderId() != null)) {

            providerUpdateMono = marketplaceService
                    .getProviderById(action.getProviderId())
                    .switchIfEmpty(Mono.just(new Provider()))
                    .map(provider -> {
                        ActionProvider actionProvider = new ActionProvider();
                        actionProvider.setName(provider.getName());
                        actionProvider.setCredentialSteps(provider.getCredentialSteps());
                        actionProvider.setDescription(provider.getDescription());
                        actionProvider.setImageUrl(provider.getImageUrl());
                        actionProvider.setUrl(provider.getUrl());

                        action.setProvider(actionProvider);
                        return action;
                    });
        } else {
            providerUpdateMono = Mono.just(action);
        }

        return providerUpdateMono
                .map(actionDTO -> {
                    newAction.setUnpublishedAction(actionDTO);
                    return newAction;
                });
    }

    @Override
    public Mono<Action> updateUnpublishedAction(String id, Action action) {

        if (id == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ID));
        }

        ActionDTO dtoFromAction = generateDTOFromAction(action);
        NewAction newAction = new NewAction();
        newAction.setUnpublishedAction(dtoFromAction);

        return repository.findById(id, MANAGE_ACTIONS)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.ACTION, id)))
                .map(dbAction -> {
                    copyNewFieldValuesIntoOldObject(dtoFromAction, dbAction.getUnpublishedAction());
                    return dbAction;
                })
                .flatMap(this::validateAndSaveActionToRepository);
    }

    @Override
    public Mono<ActionExecutionResult> executeAction(ExecuteActionDTO executeActionDTO) {

        // 1. Validate input parameters which are required for mustache replacements
        List<Param> params = executeActionDTO.getParams();
        if (!CollectionUtils.isEmpty(params)) {
            for (Param param : params) {
                // In case the parameter values turn out to be null, set it to empty string instead to allow the
                // the execution to go through no matter what.
                if (!StringUtils.isEmpty(param.getKey()) && param.getValue() == null) {
                    param.setValue("");
                }
            }
        }

        String actionId = executeActionDTO.getActionId();
        // 2. Fetch the action from the DB and check if it can be executed
        Mono<ActionDTO> actionMono = repository.findById(actionId, EXECUTE_ACTIONS)
                    .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.ACTION, actionId)))
                    .flatMap(dbAction -> {
                        ActionDTO action;
                        if (TRUE.equals(executeActionDTO.getViewMode())) {
                            action = dbAction.getPublishedAction();
                            // If the action has not been published, return error
                            if (action == null) {
                                return Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.ACTION, actionId));
                            }
                        } else {
                            action = dbAction.getUnpublishedAction();
                        }

                        // Now check for erroneous situations which would deter the execution of the action :

                        // Error out with in case of an invalid action
                        if (Boolean.FALSE.equals(action.getIsValid())) {
                            return Mono.error(new AppsmithException(
                                    AppsmithError.INVALID_ACTION,
                                    action.getName(),
                                    actionId,
                                    ArrayUtils.toString(action.getInvalids().toArray())
                            ));
                        }

                        // Error out in case of JS Plugin (this is currently client side execution only)
                        if (dbAction.getPluginType() == PluginType.JS) {
                            return Mono.error(new AppsmithException(AppsmithError.UNSUPPORTED_OPERATION));
                        }
                        return Mono.just(action);
                    })
                    .cache();

        // 3. Instantiate the implementation class based on the query type

        Mono<Datasource> datasourceMono = actionMono
                .flatMap(action -> {
                    // Global datasource requires us to fetch the datasource from DB.
                    if (action.getDatasource() != null && action.getDatasource().getId() != null) {
                        return datasourceService.findById(action.getDatasource().getId(), EXECUTE_DATASOURCES)
                                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND,
                                        FieldName.DATASOURCE,
                                        action.getDatasource().getId())));
                    }
                    // This is a nested datasource. Return as is.
                    return Mono.just(action.getDatasource());
                })
                .cache();

        Mono<Plugin> pluginMono = datasourceMono
                .flatMap(datasource -> {
                    // For embedded datasources, validate the datasource for each execution
                    if (datasource.getId() == null) {
                        return datasourceService.validateDatasource(datasource);
                    }

                    return Mono.just(datasource);
                })
                .flatMap(datasource -> {
                    Set<String> invalids = datasource.getInvalids();
                    if (!CollectionUtils.isEmpty(invalids)) {
                        log.error("Unable to execute actionId: {} because it's datasource is not valid. Cause: {}",
                                actionId, ArrayUtils.toString(invalids));
                        return Mono.error(new AppsmithException(AppsmithError.INVALID_DATASOURCE, ArrayUtils.toString(invalids)));
                    }
                    return pluginService.findById(datasource.getPluginId());
                })
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.PLUGIN)));

        Mono<PluginExecutor> pluginExecutorMono = pluginExecutorHelper.getPluginExecutor(pluginMono);

        // 4. Execute the query
        Mono<ActionExecutionResult> actionExecutionResultMono = Mono
                .zip(
                        actionMono,
                        datasourceMono,
                        pluginExecutorMono
                )
                .flatMap(tuple -> {
                    final ActionDTO action = tuple.getT1();
                    final Datasource datasource = tuple.getT2();
                    final PluginExecutor pluginExecutor = tuple.getT3();

                    DatasourceConfiguration datasourceConfiguration = null;
                    ActionConfiguration actionConfiguration = null;

                    prepareConfigurationsForExecution(action, datasource, executeActionDTO, actionConfiguration, datasourceConfiguration);

                    Integer timeoutDuration = actionConfiguration.getTimeoutInMillisecond();

                    log.debug("Execute Action called in Page {}, for action id : {}  action name : {}, {}, {}",
                            action.getPageId(), actionId, action.getName(), datasourceConfiguration,
                            actionConfiguration);

                    Mono<ActionExecutionResult> executionMono = Mono.just(datasource)
                            .flatMap(datasourceContextService::getDatasourceContext)
                            // Now that we have the context (connection details), execute the action.
                            .flatMap(
                                    resourceContext -> pluginExecutor.execute(
                                            resourceContext.getConnection(),
                                            datasourceConfiguration,
                                            actionConfiguration
                                    )
                            );

                    return executionMono
                            .onErrorResume(StaleConnectionException.class, error -> {
                                log.info("Looks like the connection is stale. Retrying with a fresh context.");
                                return datasourceContextService
                                        .deleteDatasourceContext(datasource.getId())
                                        .then(executionMono);
                            })
                            .timeout(Duration.ofMillis(timeoutDuration))
                            .onErrorMap(
                                    StaleConnectionException.class,
                                    error -> new AppsmithPluginException(
                                            AppsmithPluginError.PLUGIN_ERROR,
                                            "Secondary stale connection error."
                                    )
                            )
                            .onErrorResume(e -> {
                                log.debug("In the action execution error mode.", e);
                                ActionExecutionResult result = new ActionExecutionResult();
                                result.setBody(e.getMessage());
                                result.setIsExecutionSuccess(false);
                                // Set the status code for Appsmith plugin errors
                                if (e instanceof AppsmithPluginException) {
                                    result.setStatusCode(((AppsmithPluginException) e).getAppErrorCode().toString());
                                } else {
                                    result.setStatusCode(AppsmithPluginError.PLUGIN_ERROR.getAppErrorCode().toString());
                                }
                                return Mono.just(result);
                            });
                });

        // Populate the actionExecution result by setting the cached response and saving it to the DB
        return actionExecutionResultMono
                .flatMap(result -> {
                    Mono<ActionExecutionResult> resultMono = Mono.just(result);

                    Mono<NewAction> actionFromDbMono = repository.findById(actionId)
                            .flatMap(action -> {
                                // If the plugin execution result is successful, then cache response body in
                                // the action and save it.
                                if (TRUE.equals(result.getIsExecutionSuccess())) {
                                    // Save the result only if body exists in the body. e.g. Even though 204
                                    // is an execution success, there would be no body expected.
                                    if (result.getBody() != null) {
                                        action.getUnpublishedAction().setCacheResponse(result.getBody().toString());
                                        return repository.save(action);
                                    }
                                    // No result body exists. Return the action as is.
                                    return Mono.just(action);
                                }
                                log.debug("Action execution resulted in failure beyond the proxy with the result of {}", result);
                                return Mono.just(action);
                            });

                    return actionFromDbMono.then(resultMono);
                })
                .onErrorResume(AppsmithException.class, error -> {
                    ActionExecutionResult result = new ActionExecutionResult();
                    result.setIsExecutionSuccess(false);
                    result.setStatusCode(error.getAppErrorCode().toString());
                    result.setBody(error.getMessage());
                    return Mono.just(result);
                });
    }

    private void prepareConfigurationsForExecution(ActionDTO action,
                                                   Datasource datasource,
                                                   ExecuteActionDTO executeActionDTO,
                                                   ActionConfiguration actionConfiguration,
                                                   DatasourceConfiguration datasourceConfiguration) {
        DatasourceConfiguration datasourceConfigurationTemp;
        ActionConfiguration actionConfigurationTemp;

        //Do variable substitution
        //Do this only if params have been provided in the execute command
        if (executeActionDTO.getParams() != null && !executeActionDTO.getParams().isEmpty()) {
            Map<String, String> replaceParamsMap = executeActionDTO
                    .getParams()
                    .stream()
                    .collect(Collectors.toMap(
                            // Trimming here for good measure. If the keys have space on either side,
                            // Mustache won't be able to find the key.
                            // We also add a backslash before every double-quote or backslash character
                            // because we apply the template replacing in a JSON-stringified version of
                            // these properties, where these two characters are escaped.
                            p -> p.getKey().trim(), // .replaceAll("[\"\n\\\\]", "\\\\$0"),
                            Param::getValue,
                            // In case of a conflict, we pick the older value
                            (oldValue, newValue) -> oldValue)
                    );

            datasourceConfigurationTemp = variableSubstitution(datasource.getDatasourceConfiguration(), replaceParamsMap);
            actionConfigurationTemp = variableSubstitution(action.getActionConfiguration(), replaceParamsMap);
        } else {
            datasourceConfigurationTemp = datasource.getDatasourceConfiguration();
            actionConfigurationTemp = action.getActionConfiguration();
        }

        // If the action is paginated, update the configurations to update the correct URL.
        if (action.getActionConfiguration() != null &&
                action.getActionConfiguration().getPaginationType() != null &&
                PaginationType.URL.equals(action.getActionConfiguration().getPaginationType()) &&
                executeActionDTO.getPaginationField() != null) {
            datasourceConfiguration = updateDatasourceConfigurationForPagination(actionConfigurationTemp, datasourceConfigurationTemp, executeActionDTO.getPaginationField());
            actionConfiguration = updateActionConfigurationForPagination(actionConfigurationTemp, executeActionDTO.getPaginationField());
        } else {
            datasourceConfiguration = datasourceConfigurationTemp;
            actionConfiguration = actionConfigurationTemp;
        }

        // Filter out any empty headers
        if (actionConfiguration.getHeaders() != null && !actionConfiguration.getHeaders().isEmpty()) {
            List<Property> headerList = actionConfiguration.getHeaders().stream()
                    .filter(header -> !StringUtils.isEmpty(header.getKey()))
                    .collect(Collectors.toList());
            actionConfiguration.setHeaders(headerList);
        }
    }

    private ActionConfiguration updateActionConfigurationForPagination(ActionConfiguration actionConfiguration,
                                                                       PaginationField paginationField) {
        if (PaginationField.NEXT.equals(paginationField) || PaginationField.PREV.equals(paginationField)) {
            actionConfiguration.setPath("");
            actionConfiguration.setQueryParameters(null);
        }
        return actionConfiguration;
    }

    private DatasourceConfiguration updateDatasourceConfigurationForPagination(ActionConfiguration actionConfiguration,
                                                                               DatasourceConfiguration datasourceConfiguration,
                                                                               PaginationField paginationField) {
        if (PaginationField.NEXT.equals(paginationField)) {
            try {
                datasourceConfiguration.setUrl(URLDecoder.decode(actionConfiguration.getNext(), "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        } else if (PaginationField.PREV.equals(paginationField)) {
            datasourceConfiguration.setUrl(actionConfiguration.getPrev());
        }
        return datasourceConfiguration;
    }

    /**
     * This function replaces the variables in the Object with the actual params
     */
    @Override
    public <T> T variableSubstitution(T configuration, Map<String, String> replaceParamsMap) {
        return MustacheHelper.renderFieldValues(configuration, replaceParamsMap);
    }

    @Override
    public Mono<Action> saveAction(Action action) {
        ActionDTO unpublishedAction = this.generateDTOFromAction(action);
        return repository.findById(action.getId(), MANAGE_ACTIONS)
                .flatMap(dbAction -> {
                    dbAction.setUnpublishedAction(unpublishedAction);
                    return repository.save(dbAction);
                })
                .flatMap(savedAction -> getActionByViewMode(savedAction, false));
    }

    @Override
    public Mono<Action> findByUnpublishedNameAndPageId(String name, String pageId, AclPermission permission) {
        return repository.findByUnpublishedNameAndPageId(name, pageId, permission)
                .flatMap(action -> getActionByViewMode(action, false));
    }

    /**
     * Given a list of names of actions and pageId, find all the actions matching this criteria of name, pageId, http
     * method 'GET' (for API actions only) or have isExecuteOnLoad be true.
     *
     * @param names Set of Action names. The returned list of actions will be a subset of the actioned named in this set.
     * @param pageId Id of the Page within which to look for Actions.
     * @return A Flux of Actions that are identified to be executed on page-load.
     */
//    public Flux<Action> findOnLoadActionsInPage(Set<String> names, String pageId) {
//        final Flux<Action> getApiActions = repository
//                .findDistinctUnpublishedActionsByNameInAndPageIdAndActionConfiguration_HttpMethodAndUserSetOnLoad(names, pageId, "GET", false);
//
//        final Flux<Action> explicitOnLoadActions = repository
//                .findDistinctActionsByNameInAndPageIdAndExecuteOnLoadTrue(names, pageId);
//
//        return getApiActions.concatWith(explicitOnLoadActions);
//    }
}
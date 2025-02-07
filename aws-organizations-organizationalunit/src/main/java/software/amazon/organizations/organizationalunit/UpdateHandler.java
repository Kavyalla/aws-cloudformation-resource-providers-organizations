package software.amazon.organizations.organizationalunit;

import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.TagResourceRequest;
import software.amazon.awssdk.services.organizations.model.UntagResourceRequest;
import software.amazon.awssdk.services.organizations.model.UpdateOrganizationalUnitRequest;
import software.amazon.awssdk.services.organizations.model.UpdateOrganizationalUnitResponse;
import software.amazon.awssdk.services.organizations.model.Tag;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.organizations.utils.OrgsLoggerWrapper;

import java.util.HashSet;
import java.util.Set;

public class UpdateHandler extends BaseHandlerStd {
    private OrgsLoggerWrapper log;

    public ProgressEvent<ResourceModel, CallbackContext> handleRequest(
        final AmazonWebServicesClientProxy awsClientProxy,
        final ResourceHandlerRequest<ResourceModel> request,
        final CallbackContext callbackContext,
        final ProxyClient<OrganizationsClient> orgsClient,
        final OrgsLoggerWrapper logger
    ) {

        this.log = logger;
        final ResourceModel previousModel = request.getPreviousResourceState();
        final ResourceModel model = request.getDesiredResourceState();

        String ouId = model.getId();
        String name = model.getName();

        // Check that the previousModel OU id is equal to the desiredModel OU id. If not then return NotUpdatable exception
        if (previousModel != null) {
            if (previousModel.getId() != null && !ouId.equals(previousModel.getId())) {
                return ProgressEvent.failed(model, callbackContext, HandlerErrorCode.NotUpdatable,
                    String.format("Organizational unit [%s] cannot be updated as the id was changed", name));
            }
        }

        // Check to see if previous model exist before calling getTags()
        Set<Tag> allPreviousTags;
        if (previousModel != null) {
            Set<software.amazon.organizations.organizationalunit.Tag> previousTags = previousModel.getTags();
            allPreviousTags = TagsHelper.mergeTags(
                    TagsHelper.convertOrganizationalUnitTagToOrganizationTag(previousTags),
                    request.getPreviousResourceTags());
        } else {
            allPreviousTags = new HashSet<>();
        }

        Set<software.amazon.organizations.organizationalunit.Tag> newTags = model.getTags();
        Set<Tag> allNewTags = TagsHelper.mergeTags(
                TagsHelper.convertOrganizationalUnitTagToOrganizationTag(newTags),
                request.getDesiredResourceTags());

        // Call UpdateOrganizationalUnit API
        logger.log(String.format("Requesting UpdateOrganizationalUnit w/ id: %s and name: %s.%n", ouId, name));
        return ProgressEvent.progress(model, callbackContext)
            .then(progress ->
                awsClientProxy.initiate("AWS-Organizations-OrganizationalUnit::UpdateOrganizationalUnit", orgsClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(Translator::translateToUpdateOrganizationalUnitRequest)
                .makeServiceCall(this::updateOrganizationalUnit)
                .handleError((organizationsRequest, e, proxyClient1, model1, context) -> handleErrorInGeneral(organizationsRequest, e, proxyClient1, model1, context, logger, Constants.Action.UPDATE_OU, Constants.Handler.UPDATE))
                .progress()
            )
            .then(progress -> handleTagging(awsClientProxy, model, callbackContext, allNewTags, allPreviousTags, ouId, orgsClient, logger))
            .then(progress -> new ReadHandler().handleRequest(awsClientProxy, request, callbackContext, orgsClient, logger));
    }

    protected UpdateOrganizationalUnitResponse updateOrganizationalUnit(final UpdateOrganizationalUnitRequest updateOrganizationalUnitRequest, final ProxyClient<OrganizationsClient> orgsClient) {
        log.log(String.format("Calling updateOrganizationalUnit API for OU [%s].", updateOrganizationalUnitRequest.organizationalUnitId()));
        final UpdateOrganizationalUnitResponse updateOrganizationalUnitResponse = orgsClient.injectCredentialsAndInvokeV2(updateOrganizationalUnitRequest, orgsClient.client()::updateOrganizationalUnit);
        return updateOrganizationalUnitResponse;
    }

    // Handles creating new tags, updating existing tags, and deleting old tags
    private ProgressEvent<ResourceModel, CallbackContext> handleTagging(
            final AmazonWebServicesClientProxy awsClientProxy,
            final ResourceModel model,
            final CallbackContext callbackContext,
            final Set<Tag> newTags,
            final Set<Tag> previousTags,
            final String organizationalUnitId,
            final ProxyClient<OrganizationsClient> orgsClient,
            final OrgsLoggerWrapper logger
    ) {
        // Includes all old tags that do not exist in new tag list
        final Set<String> tagsToRemove = TagsHelper.getTagKeysToRemove(previousTags, newTags);

        // Excluded all old tags that do exist in new tag list
        final Set<Tag> tagsToAddOrUpdate = TagsHelper.getTagsToAddOrUpdate(previousTags, newTags);

        // Delete tags only if tagsToRemove is not empty
        if (!tagsToRemove.isEmpty()) {
            logger.log(String.format("Calling untagResource API for OU [%s].", model.getName()));
            UntagResourceRequest untagResourceRequest = Translator.translateToUntagResourceRequest(tagsToRemove, organizationalUnitId);
            try {
                awsClientProxy.injectCredentialsAndInvokeV2(untagResourceRequest, orgsClient.client()::untagResource);
            } catch (Exception e) {
                return handleErrorInGeneral(untagResourceRequest, e, orgsClient, model, callbackContext, logger, Constants.Action.UNTAG_RESOURCE, Constants.Handler.UPDATE);
            }
        }

        // Add tags only if tagsToAddOrUpdate is not empty.
        if (!tagsToAddOrUpdate.isEmpty()) {
            logger.log(String.format("Calling tagResource API for OU [%s].", model.getName()));
            TagResourceRequest tagResourceRequest = Translator.translateToTagResourceRequest(tagsToAddOrUpdate, organizationalUnitId);
            try {
                awsClientProxy.injectCredentialsAndInvokeV2(tagResourceRequest, orgsClient.client()::tagResource);
            } catch(Exception e) {
                return handleErrorInGeneral(tagResourceRequest, e, orgsClient, model, callbackContext, logger, Constants.Action.TAG_RESOURCE, Constants.Handler.UPDATE);
            }
        }

        return ProgressEvent.progress(model, callbackContext);
    }
}

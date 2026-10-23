<#ftl output_format="HTML">
<#-- The provider's required-action list and localized labels remain authoritative. -->
<#outputformat "plainText">
<#assign requiredActionsText><#if requiredActions??><#list requiredActions as action>${msg("requiredAction.${action}")}<#sep>, </#list></#if></#assign>
</#outputformat>
<#import "template.ftl" as layout>
<@layout.accountEmail title=msg("executeActionsTitle") introduction=msg("executeActionsIntro", realmName, requiredActionsText) expiration=msg("gmExpiration", linkExpirationFormatter(linkExpiration)) security=msg("executeActionsSecurity") actionLabel=msg("executeActionsAction") link=link />

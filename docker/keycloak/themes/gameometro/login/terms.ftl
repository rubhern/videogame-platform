<#-- Keycloak 26.7 keycloak.v2 terms.ftl (#548). An account created with Google or Microsoft never saw
     the registration form, so this required action presents the same approved notice (the realm's
     termsTitle and termsText) the way the registration form does: the registration title, the notice
     collapsed under its heading, and its closing legal-links paragraph visible outside the disclosure.
     Only the presentation and the button copy differ from the parent; the native accept and cancel
     actions are unchanged. -->
<#import "template.ftl" as layout>
<#import "buttons.ftl" as buttons>

<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        ${msg("registerTitle")}
    <#elseif section = "form">
    <#assign notice = kcSanitize(msg("termsText"))?replace('<a href=', '<a target="_blank" rel="noopener" href=')>
    <#assign linksStart = notice?last_index_of("<p>")>
    <div id="gm-terms" class="gm-terms-notice">
        <details class="gm-registration-disclosure">
            <summary>${msg("termsTitle")}</summary>
            <div id="gm-terms-text">
                <#if linksStart gt 0>${notice[0..<linksStart]?no_esc}<#else>${notice?no_esc}</#if>
            </div>
        </details>
        <#if linksStart gt 0><div class="gm-registration-links">${notice[linksStart..]?no_esc}</div></#if>
    </div>
    <form class="${properties.kcFormClass!}" action="${url.loginAction}" method="POST">
        <@buttons.actionGroup horizontal=true>
            <@buttons.button name="accept" id="kc-accept" label="gmTermsAccept"/>
            <@buttons.button name="cancel" id="kc-decline" label="gmTermsDecline" type="secondary"/>
        </@buttons.actionGroup>
    </form>
    <div class="clearfix"></div>
    </#if>
</@layout.registrationLayout>

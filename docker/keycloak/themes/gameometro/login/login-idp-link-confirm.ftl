<#-- Keycloak 26.7 base login-idp-link-confirm.ftl (#548): the same two native actions, with adding
     the provider to the existing account first and primary, and choosing another email secondary.
     Linking still requires the existing account's password on the next screen. -->
<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "header">
        ${msg("confirmLinkIdpTitle")}
    <#elseif section = "form">
        <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!} gm-link-actions">
                <button type="submit" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" name="submitAction" id="linkAccount" value="linkAccount">${msg("confirmLinkIdpContinue", idpDisplayName)}</button>
                <#if !hideReviewButton?has_content>
                    <button type="submit" class="${properties.kcButtonClass!} ${properties.kcButtonSecondaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" name="submitAction" id="updateProfile" value="updateProfile">${msg("confirmLinkIdpReviewProfile")}</button>
                </#if>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>

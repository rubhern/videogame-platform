<#ftl output_format="HTML">
<#import "template.ftl" as layout>
<@layout.accountEmail title=msg("emailVerificationTitle") introduction=msg("emailVerificationIntro", realmName) expiration=msg("gmExpiration", linkExpirationFormatter(linkExpiration)) security=msg("emailVerificationSecurity") actionLabel=msg("emailVerificationAction") link=link />

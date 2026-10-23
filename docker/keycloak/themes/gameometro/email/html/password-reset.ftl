<#ftl output_format="HTML">
<#import "template.ftl" as layout>
<@layout.accountEmail title=msg("passwordResetTitle") introduction=msg("passwordResetIntro", realmName) expiration=msg("passwordResetExpiration", linkExpirationFormatter(linkExpiration)) security=msg("passwordResetSecurity") actionLabel=msg("passwordResetAction") link=link />

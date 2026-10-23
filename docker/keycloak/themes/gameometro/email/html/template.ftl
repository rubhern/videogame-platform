<#ftl output_format="HTML">
<#-- Static email HTML. Palette mirrors frontend/src/styles/index.css; no frontend CSS is loaded. -->
<#macro emailLayout>
<!doctype html>
<html lang="${locale.language}" dir="${(ltr)?then('ltr','rtl')}">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="color-scheme" content="dark light">
    <title>Gameómetro</title>
</head>
<body bgcolor="#06080d" style="margin:0; padding:0; background-color:#06080d; -webkit-text-size-adjust:100%;">
<div role="main" lang="${locale.language}" style="color:#f1f4fa; font-family:Arial,Helvetica,sans-serif;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#06080d" style="width:100%; border-collapse:collapse; background-color:#06080d;">
        <tr><td align="center" style="padding:28px 12px;">
            <!--[if mso]><table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0"><tr><td><![endif]-->
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#0c1018" style="width:100%; max-width:600px; border-collapse:collapse; background-color:#0c1018; border:1px solid #232c3a;">
                <tr><td bgcolor="#0c1018" style="padding:24px; border-top:3px solid #ff6a3d; border-bottom:1px solid #232c3a; color:#f1f4fa;">
                    <#if url??>
                    <img src="${url.resourcesUrl}/img/gameometro-logo.png" width="219" height="40" alt="Gameómetro" style="display:block; width:219px; max-width:100%; height:auto; border:0; color:#f1f4fa; font-size:24px; font-weight:700;">
                    </#if>
                    <div style="padding-top:6px; font-size:12px; line-height:18px; color:#a6b1c4;">${msg("gmAccountContext")}</div>
                </td></tr>
                <tr><td style="padding:28px 24px 24px; font-size:16px; line-height:26px; color:#a6b1c4;">
                    <#nested>
                </td></tr>
                <tr><td style="padding:20px 24px; border-top:1px solid #232c3a; font-size:12px; line-height:20px; color:#a6b1c4;">
                    ${msg("gmAutomaticMessage")}
                </td></tr>
            </table>
            <!--[if mso]></td></tr></table><![endif]-->
        </td></tr>
    </table>
</div>
</body>
</html>
</#macro>

<#macro accountEmail title introduction expiration="" security="" actionLabel="" link="">
<@emailLayout>
    <h1 style="margin:0 0 24px; font-family:Arial,Helvetica,sans-serif; font-size:30px; line-height:36px; font-weight:700; color:#f1f4fa;">${title}</h1>
    <p style="margin:0 0 12px; color:#f1f4fa;">${msg("gmGreeting")}</p>
    <p style="margin:0 0 24px; color:#a6b1c4;">${introduction}</p>
    <#if link?has_content>
    <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse; margin:0 0 20px;">
        <tr><td align="center" bgcolor="#ff6a3d" style="background-color:#ff6a3d; border-radius:4px;">
            <a href="${link}" style="display:inline-block; padding:14px 16px; border:2px solid #ff6a3d; border-radius:4px; font-family:Arial,Helvetica,sans-serif; font-size:16px; line-height:22px; font-weight:700; text-decoration:none; color:#06080d; background-color:#ff6a3d; mso-padding-alt:0;">${actionLabel}</a>
        </td></tr>
    </table>
    <p style="margin:0 0 24px; font-size:14px; line-height:22px; color:#a6b1c4;">${expiration}</p>
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="width:100%; border-collapse:collapse; margin:0 0 24px;">
        <tr><td style="padding:16px; border-left:3px solid #9db3ff; background-color:#111927; color:#dbe7ff; font-size:14px; line-height:22px;">
            <p style="margin:0;">${security}</p>
        </td></tr>
    </table>
    <p style="margin:0 0 6px; font-size:13px; line-height:21px; color:#a6b1c4;">${msg("gmAlternativeAction")}</p>
    <p style="margin:0 0 24px; font-size:14px; line-height:22px;"><a href="${link}" style="color:#9db3ff; text-decoration:underline;">${actionLabel}</a></p>
    </#if>
    <p style="margin:0; color:#f1f4fa; font-size:14px; line-height:22px;">Gameómetro</p>
</@emailLayout>
</#macro>

<#macro content>
  <#-- Gameómetro's story beside the inherited Keycloak forms: product presentation only, with no form or flow logic. -->
  <p class="gm-exit"><a href="${properties.applicationOrigin}/"><span aria-hidden="true">←</span> ${msg("gmBackToCatalogue")}</a></p>
  <template id="gm-registration-disclosure">
    <details class="gm-registration-disclosure">
      <summary></summary>
    </details>
  </template>
  <div class="gm-story">
    <p class="gm-claim"><span>${msg("gmClaim")}</span> <span class="gm-claim-echo">${msg("gmClaimEcho")}</span></p>
    <p class="gm-lede">${msg("gmLede")}</p>
    <div class="gm-scale">
      <p class="gm-scale-title">${msg("gmScaleTitle")}</p>
      <div class="gm-scale-bar" aria-hidden="true"><#list 1..10 as value><i></i></#list></div>
      <p class="gm-scale-ends"><span>${msg("gmScaleFreeze")}</span> <span>${msg("gmScaleBurn")}</span></p>
    </div>
  </div>
</#macro>

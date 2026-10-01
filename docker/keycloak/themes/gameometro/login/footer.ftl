<#macro content>
  <#-- Gameómetro's story beside the inherited Keycloak forms: product copy only, no form or flow logic. -->
  <div class="gm-stage">
    <div class="gm-story">
      <p class="gm-claim"><span>${msg("gmClaim")}</span> <span class="gm-claim-accent">${msg("gmClaimAccent")}</span></p>
      <p class="gm-lede">${msg("gmLede")}</p>
      <div class="gm-scale">
        <p class="gm-scale-title">${msg("gmScaleTitle")}</p>
        <div class="gm-scale-bar" aria-hidden="true"><#list 1..10 as value><i></i></#list></div>
        <p class="gm-scale-ends"><span>${msg("gmScaleFreeze")}</span> <span>${msg("gmScaleBurn")}</span></p>
      </div>
    </div>
  </div>
  <p class="gm-exit"><a href="${properties.applicationOrigin}/"><span aria-hidden="true">←</span> ${msg("gmBackToCatalogue")}</a></p>
</#macro>

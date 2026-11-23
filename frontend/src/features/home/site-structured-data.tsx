import { officialSocialProfiles } from "../../shared/brand/social-profiles";
import { StructuredDataScript } from "../../shared/seo/structured-data-script";

const siteUrl = "https://gameometro.com/";
const organizationId = siteUrl + "#organization";
const siteData = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "Organization",
      "@id": organizationId,
      name: "Gameómetro",
      url: siteUrl,
      logo: siteUrl + "apple-touch-icon.png",
      sameAs: officialSocialProfiles.map(({ url }) => url),
    },
    {
      "@type": "WebSite",
      name: "Gameómetro",
      url: siteUrl,
      publisher: { "@id": organizationId },
    },
  ],
};

export function SiteStructuredData() {
  return <StructuredDataScript data={siteData} />;
}

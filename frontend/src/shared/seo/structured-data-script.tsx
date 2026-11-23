/** Escape less-than signs so data cannot close the JSON-LD element. */
export function StructuredDataScript({ data }: { data: object }) {
  const json = JSON.stringify(data).replace(/</g, "\\u003c");
  return <script type="application/ld+json">{json}</script>;
}

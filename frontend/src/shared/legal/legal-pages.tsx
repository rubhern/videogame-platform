import { Link } from "react-router-dom";

import proposed from "./proposed-texts.json";
import { legalPublicationApproved } from "./publication";

type DocumentKey = keyof typeof proposed.documents;

function Inline({ text }: { text: string }) {
  return text.split(/(\[[^\]]+\]\([^)]+\)|`[^`]+`)/g).map((part, index) => {
    const link = part.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
    const href = link?.[2];
    if (link && href && /^(https:\/\/|mailto:)/.test(href)) {
      return <a key={index} href={href} rel="noreferrer">{link[1]}</a>;
    }
    if (part.startsWith("`") && part.endsWith("`")) return <code key={index}>{part.slice(1, -1)}</code>;
    return part;
  });
}

export function LegalPage({ document }: { document: DocumentKey }) {
  const content = proposed.documents[document];
  return (
    <article className="page-container page-section legal-document">
      {!legalPublicationApproved && (
        <aside className="notice notice-info" aria-label="Estado del texto">
          <strong>Borrador para revisión. No está en vigor.</strong>
          <p>La publicación requiere aprobar los textos y verificar los proveedores y mecanismos operativos.</p>
        </aside>
      )}
      <h1 className="page-title">{content.title}</h1>
      {content.blocks.map((block, index) => {
        if (block.kind === "heading" && "text" in block) return <h2 key={index}>{block.text}</h2>;
        if (block.kind === "table" && "rows" in block) return (
          <div className="legal-table" key={index} tabIndex={0} role="region" aria-label="Inventario de cookies">
            <table>
              <caption>Cookies de autenticación, seguridad e idioma</caption>
              <thead><tr>{(block.rows[0] ?? []).map((cell) => <th key={cell} scope="col"><Inline text={cell} /></th>)}</tr></thead>
              <tbody>{block.rows.slice(1).map((row, rowIndex) => (
                <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}><Inline text={cell} /></td>)}</tr>
              ))}</tbody>
            </table>
          </div>
        );
        return "text" in block ? <p key={index}><Inline text={block.text} /></p> : null;
      })}
      <Link to="/">Volver a Gameómetro</Link>
    </article>
  );
}

import { Link } from "react-router-dom";

import published from "./public-texts.json";

type DocumentKey = keyof typeof published.documents;

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

type Block = (typeof published.documents)[DocumentKey]["blocks"][number];

/**
 * A table is named by the section heading it follows; the opening inventory, before any heading,
 * keeps its established name. Each inventory therefore stays a distinctly named region (#507).
 */
function tableCaption(preceding: readonly Block[]): string {
  for (let index = preceding.length - 1; index >= 0; index -= 1) {
    const block = preceding[index];
    if (block?.kind === "heading" && "text" in block) return block.text;
    if (block?.kind === "table") break;
  }
  return "Cookies de autenticación, seguridad e idioma";
}

export function LegalPage({ document }: { document: DocumentKey }) {
  const content = published.documents[document];
  return (
    <article className="page-container page-section legal-document">
      <h1 className="page-title">{content.title}</h1>
      {content.blocks.map((block, index) => {
        if (block.kind === "heading" && "text" in block) return <h2 key={index}>{block.text}</h2>;
        if (block.kind === "table" && "rows" in block) {
          const caption = tableCaption(content.blocks.slice(0, index));
          return (
          <div className="legal-table" key={index} tabIndex={0} role="region" aria-label={`Inventario: ${caption}`}>
            <table>
              <caption>{caption}</caption>
              <thead><tr>{(block.rows[0] ?? []).map((cell) => <th key={cell} scope="col"><Inline text={cell} /></th>)}</tr></thead>
              <tbody>{block.rows.slice(1).map((row, rowIndex) => (
                <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}><Inline text={cell} /></td>)}</tr>
              ))}</tbody>
            </table>
          </div>
          );
        }
        return "text" in block ? <p key={index}><Inline text={block.text} /></p> : null;
      })}
      <Link to="/">Volver a Gameómetro</Link>
    </article>
  );
}

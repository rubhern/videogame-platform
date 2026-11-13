function linkBrandToHome() {
  const brand = document.getElementById("kc-header-wrapper");
  const returnLink = document.querySelector(".gm-exit a");
  if (!brand || !returnLink) {
    return;
  }

  const home = document.createElement("a");
  home.href = returnLink.href;
  home.append(...brand.childNodes);
  brand.append(home);
}

function collapseRegistrationNotice() {
  const notice = document.getElementById("kc-registration-terms-text");
  const template = document.getElementById("gm-registration-disclosure");
  // Keycloak renders the approved heading as text directly before the notice.
  const heading = notice?.previousSibling;
  if (!template || heading?.nodeType !== Node.TEXT_NODE || !heading.textContent.trim()) {
    return;
  }

  const disclosure = template.content.firstElementChild.cloneNode(true);
  disclosure.querySelector("summary").textContent = heading.textContent.trim();
  const legalLinks = [...notice.querySelectorAll('a[href^="https://"], a[href^="http://"]')];
  const linksParagraph = legalLinks[0]?.parentElement;
  if (linksParagraph?.matches("p") &&
      linksParagraph === notice.lastElementChild &&
      legalLinks.every((link) => link.parentElement === linksParagraph)) {
    linksParagraph.classList.add("gm-registration-links");
    notice.parentElement.append(linksParagraph);
  }

  for (const link of legalLinks) {
    link.target = "_blank";
    link.relList.add("noopener");
  }

  notice.replaceWith(disclosure);
  disclosure.append(notice);
  heading.remove();
}

function emphasizeAcceptedTerms() {
  const label = document.querySelector('label[for="termsAccepted"]');
  const prefix = "He leído y acepto ";
  if (label?.textContent.startsWith(prefix)) {
    const terms = document.createElement("strong");
    terms.textContent = label.textContent.slice(prefix.length);
    label.replaceChildren(prefix, terms);
  }
}

function describeRegistrationErrors() {
  const form = document.getElementById("kc-register-form");
  if (!form) {
    return;
  }

  for (const [fieldId, errorId] of [["email", "input-error-email"], ["termsAccepted", "input-error-terms-accepted"]]) {
    const field = form.querySelector(`#${fieldId}[aria-invalid="true"]`);
    if (field && form.querySelector(`#${errorId}`)) {
      const description = field.getAttribute("aria-describedby");
      const descriptions = new Set(description?.split(/\s+/).filter(Boolean));
      descriptions.add(errorId);
      field.setAttribute("aria-describedby", [...descriptions].join(" "));
    }
  }
}

document.addEventListener("DOMContentLoaded", () => {
  linkBrandToHome();
  collapseRegistrationNotice();
  emphasizeAcceptedTerms();
  describeRegistrationErrors();
});

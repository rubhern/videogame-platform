import { consentStore } from "./consent";

/** The footer's way to review or withdraw the optional measurement choices. */
export function CookieSettingsButton() {
  return (
    <li>
      <button className="site-footer-action" onClick={consentStore.review} type="button">
        Configurar cookies
      </button>
    </li>
  );
}

import { consentStore } from "./consent";
import { useAnalyticsMeasurementId } from "./use-analytics-measurement-id";

/** The footer's way to review or withdraw the analytics choice; absent when nothing is measured. */
export function CookieSettingsButton() {
  const measurementId = useAnalyticsMeasurementId();
  if (measurementId === null) return null;
  return (
    <li>
      <button className="site-footer-action" onClick={consentStore.review} type="button">
        Configurar cookies
      </button>
    </li>
  );
}

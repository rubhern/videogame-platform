import { platformIcon } from "../../shared/catalogue/taxonomy-icons";
import { SelectIcon } from "../../shared/ui/select-icon";
import type { PlatformOption } from "./interests";
import { selectablePlatforms } from "./selectable-platforms";

type PlatformPickerProps = {
  /** The local taxonomy's selectable platforms, as the API orders them. */
  platforms: readonly PlatformOption[];
  selected: readonly string[];
  onToggle: (platformId: string) => void;
  /** Names the group for assistive technology, for example `Tus plataformas`. */
  legend: string;
  disabled?: boolean;
};

function PlatformTile({
  platform,
  checked,
  onToggle,
  disabled,
}: {
  platform: PlatformOption;
  checked: boolean;
  onToggle: () => void;
  disabled: boolean;
}) {
  const icon = platformIcon(platform.platformId, platform.name);
  return (
    <li>
      <label className="platform-tile" data-recognized={icon === "platform" ? undefined : "true"}>
        <input
          checked={checked}
          className="platform-tile-input"
          disabled={disabled}
          onChange={onToggle}
          type="checkbox"
        />
        <span aria-hidden="true" className="platform-tile-mark">
          <SelectIcon name={icon} />
        </span>
        <span className="platform-tile-name">{platform.name}</span>
        {/* The tick is a shape as well as the ice light, so selection never relies on colour. */}
        <span aria-hidden="true" className="platform-tile-check" />
      </label>
    </li>
  );
}

/**
 * The preferred-platform choice shared by the first-run welcome and **Mis plataformas**: one
 * native checkbox per platform of the local taxonomy, drawn as a tile with its mark. Only
 * platforms with an owner-provided mark are offered (owner decision, #295); the rest of the
 * taxonomy is never selectable. Every tile still comes from the API, so nothing is a hard-coded
 * console list: only which catalogue platforms are presented is local.
 */
export function PlatformPicker({ platforms, selected, onToggle, legend, disabled = false }: PlatformPickerProps) {
  return (
    <fieldset className="platform-picker">
      <legend className="sr-only">{legend}</legend>
      <ul className="platform-tiles">
        {selectablePlatforms(platforms).map((platform) => (
          <PlatformTile
            checked={selected.includes(platform.platformId)}
            disabled={disabled}
            key={platform.platformId}
            onToggle={() => onToggle(platform.platformId)}
            platform={platform}
          />
        ))}
      </ul>
    </fieldset>
  );
}

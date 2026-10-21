import { Navigate, useSearchParams } from "react-router-dom";

import { explorerRequestSearch } from "../features/platform-preferences/discovery-requests";
import { resolvePlatformFilter } from "../features/platform-preferences/interests";
import { useInterestsSwitch } from "../features/platform-preferences/interests-switch";
import { useInterests } from "../features/platform-preferences/use-platform-preferences";
import type { CalendarDayPreview } from "../features/releases/release-calendar-day";
import { chosenDay } from "../features/releases/release-calendar-model";
import {
  calendarSearchPath,
  dayPreviewSearch,
  readCalendarSearch,
  toCalendarQuery,
} from "../features/releases/release-calendar-search";
import {
  ReleaseCalendarShell,
  type ReleaseCalendarShellState,
} from "../features/releases/release-calendar-shell";
import { toFailureState } from "../features/releases/releases-failure";
import { useReleaseCalendarQuery } from "../features/releases/use-release-calendar-query";
import { useReleasesQuery } from "../features/releases/use-releases-query";

/** A day the explorer never reads, so a disabled preview keeps a stable, valid search. */
const NO_DAY = "0001-01-01";

export function ReleaseCalendarPage() {
  const [searchParams] = useSearchParams();
  const search = readCalendarSearch(searchParams);
  // Preferred platforms resolve into the explicit filter the public reads carry, as the explorer's
  // do, and the reads wait for them so a first view never shows every platform and then narrows.
  const interests = useInterests();
  const { enabled } = useInterestsSwitch();
  const platformFilter = resolvePlatformFilter(search.platformIds, enabled, interests);
  const ready = interests.status !== "pending";
  const calendar = useReleaseCalendarQuery(toCalendarQuery(search, platformFilter.platformIds), ready);

  const model = calendar.data !== undefined && !calendar.isPlaceholderData ? calendar.data : null;
  // A day that has gone by, or of another month, is never chosen.
  const selectedDay = model === null ? null : chosenDay(model, search.day);
  const selectedCount =
    model?.days.find((day) => day.date === selectedDay)?.gameCount ?? 0;
  const preview = useReleasesQuery(
    explorerRequestSearch(dayPreviewSearch(search, selectedDay ?? NO_DAY, selectedCount), enabled, interests),
    ready && selectedDay !== null && selectedCount > 0,
  );

  // A month the API rejects has gone by or lies beyond the forward bound: the calendar returns to
  // the current month, keeping the filters, and never presents the rejected one.
  if (calendar.isError && calendar.error.code === "FILTER_INVALID" && search.month !== null) {
    return <Navigate replace to={calendarSearchPath(search, { month: null, day: null })} />;
  }

  let state: ReleaseCalendarShellState;
  if (calendar.isPending || calendar.isPlaceholderData) {
    state = { status: "loading" };
  } else if (calendar.isError) {
    state = toFailureState(calendar.error);
  } else {
    state = { status: "ready", model: calendar.data };
  }

  let dayPreview: CalendarDayPreview;
  if (preview.isError) {
    dayPreview = { status: "error", correlationId: preview.error.correlationId };
  } else if (preview.data === undefined || preview.isPlaceholderData) {
    dayPreview = { status: "loading" };
  } else {
    dayPreview = { status: "ready", items: preview.data.items };
  }

  return (
    <ReleaseCalendarShell
      dayPreview={dayPreview}
      interests={interests}
      onRetry={() => {
        void calendar.refetch();
      }}
      onRetryDay={() => {
        void preview.refetch();
      }}
      platformFilter={platformFilter}
      previousModel={calendar.isPlaceholderData ? (calendar.data ?? null) : null}
      search={search}
      selectedDay={selectedDay}
      state={state}
    />
  );
}

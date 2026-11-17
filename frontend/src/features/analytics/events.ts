/**
 * The closed product event dictionary (#507; questions in docs/research/product-analytics.md).
 * Every property takes a bounded value. Events never carry a search term, a score, a game or
 * account identifier, an email or free text; the type system refuses anything else.
 */
type YesNo = "yes" | "no";
export type CountBucket = "0" | "1" | "2" | "3+";
export type PreferenceCountBucket = "0" | "1" | "2-3" | "4+";
export type PositionBucket = "1" | "2-3" | "4-6" | "7+";
export type ResultsBucket = "0" | "1-10" | "11+";
export type FeaturedOffset = "-3" | "-2" | "-1" | "0" | "+1" | "+2" | "+3" | "beyond";

export type ProductEvents = {
  rating_open: { authenticated: YesNo; has_rating: YesNo };
  rating_sign_in: Record<string, never>;
  rating_saved: {
    action: "create" | "update" | "delete";
    mode: "direct" | "platforms";
    platform_count: CountBucket;
  };
  rating_platforms_open: { has_rating: YesNo };
  rating_abandoned: { step: "scale" | "platforms" };
  my_platforms_saved: { count: PreferenceCountBucket };
  interests_filter_toggle: { state: "on" | "off" };
  featured_period: { period: "week" | "month" };
  featured_navigate: {
    period: "week" | "month";
    direction: "previous" | "next" | "current";
    offset: FeaturedOffset;
  };
  calendar_interaction: { action: "month_change" | "day_select" };
  explore_filter: {
    filter: "range" | "sort" | "platform" | "region" | "approximate_dates" | "page";
  };
  search_results: { results: ResultsBucket };
  list_item_open: { list: "featured_hero" | "featured"; position: PositionBucket };
  sign_in_start: { intent: "login" | "register" | "rating" };
  sign_in_return: { intent: "login" | "register" | "rating" };
};

export type ProductEventName = keyof ProductEvents;
export type EventSink = (name: ProductEventName, params: Readonly<Record<string, string>>) => void;

let sink: EventSink | null = null;

/** Installed by the analytics boundary only while the visitor has granted consent. */
export function setEventSink(next: EventSink | null) {
  sink = next;
}

/** Reports one product event; without consent (or analytics) it does nothing at all. */
export function trackEvent<Name extends ProductEventName>(
  name: Name,
  ...params: ProductEvents[Name] extends Record<string, never> ? [] : [ProductEvents[Name]]
) {
  sink?.(name, (params[0] ?? {}) as Readonly<Record<string, string>>);
}

export function countBucket(count: number): CountBucket {
  return count <= 0 ? "0" : count === 1 ? "1" : count === 2 ? "2" : "3+";
}

export function preferenceCountBucket(count: number): PreferenceCountBucket {
  return count <= 0 ? "0" : count === 1 ? "1" : count <= 3 ? "2-3" : "4+";
}

export function positionBucket(position: number): PositionBucket {
  return position <= 1 ? "1" : position <= 3 ? "2-3" : position <= 6 ? "4-6" : "7+";
}

export function resultsBucket(total: number): ResultsBucket {
  return total <= 0 ? "0" : total <= 10 ? "1-10" : "11+";
}

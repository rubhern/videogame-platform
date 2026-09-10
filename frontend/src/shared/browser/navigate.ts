/** Full-page navigation to a server-owned path, isolated here so it can be observed in tests. */
export function assignLocation(url: string): void {
  window.location.assign(url);
}

import { createContext } from "react";

export const WishlistContext = createContext<{
  pending: boolean;
  csrfToken: string | null;
  savedByGame: ReadonlyMap<string, boolean>;
} | null>(null);

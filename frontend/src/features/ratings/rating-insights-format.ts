const integerFormatter = new Intl.NumberFormat("es-ES", { useGrouping: "always" });
const decimalFormatter = new Intl.NumberFormat("es-ES", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
export const number = (value: number) => integerFormatter.format(value);
export const decimal = (value: number) => decimalFormatter.format(value);

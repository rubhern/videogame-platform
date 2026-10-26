// Every displayed credential form requires a fresh opt-in, including a remembered username hint.
document.addEventListener("DOMContentLoaded", () => {
  const checkbox = document.getElementById("rememberMe");
  if (checkbox) {
    checkbox.checked = false;
    checkbox.defaultChecked = false;
  }
});

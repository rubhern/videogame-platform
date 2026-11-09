import { useMutation } from "@tanstack/react-query";
import { useEffect, useRef, useState, type SubmitEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { FeedbackError, submitFeedback, type FeedbackCategory } from "../features/feedback/feedback-api";
import { HeroTitle } from "../shared/ui/hero-title";

const title = "Contacto y sugerencias";
const categories: Record<FeedbackCategory, string> = {
  suggestion: "Sugerencia",
  bug: "Error o problema",
  incorrect_game_information: "Datos incorrectos de un juego",
  other: "Otro comentario",
};
const fieldErrors = {
  category: "Elige un motivo para tu mensaje.",
  message: "Escribe un mensaje de entre 1 y 2.000 caracteres.",
  email: "Introduce una dirección de correo válida o deja el campo vacío.",
};
type Field = keyof typeof fieldErrors;

function fieldFor(error: FeedbackError): Field | null {
  switch (error.pointer) {
    case "/body/category": return "category";
    case "/body/message": return "message";
    case "/body/email": return "email";
    default: return null;
  }
}

export function ContactPage() {
  const [params] = useSearchParams();
  const [category, setCategory] = useState<FeedbackCategory | "">(
    params.get("motivo") === "datos" ? "incorrect_game_information" : "",
  );
  const [message, setMessage] = useState("");
  const [email, setEmail] = useState("");
  const [invalid, setInvalid] = useState<Field | null>(null);
  const [status, setStatus] = useState<"idle" | "sent" | "unconfirmed" | "busy" | "throttled" | "rejected">("idle");
  const [retryAfter, setRetryAfter] = useState(0);
  const [cooldown, setCooldown] = useState(false);
  const form = useRef<HTMLFormElement>(null);
  const sending = useRef(false);
  const mutation = useMutation({
    mutationFn: () => {
      if (!category) throw new Error("Missing feedback category");
      return submitFeedback({ category, message: message.trim(), ...(email.trim() ? { email: email.trim() } : {}) });
    },
    retry: false,
    gcTime: 0,
    networkMode: "always",
  });

  useEffect(() => {
    const previousTitle = document.title;
    document.title = `${title} · Gameómetro`;
    return () => { document.title = previousTitle; };
  }, []);

  useEffect(() => {
    if (!cooldown) return;
    const timer = window.setTimeout(() => setCooldown(false), retryAfter * 1000);
    return () => window.clearTimeout(timer);
  }, [cooldown, retryAfter]);

  function invalidate(field: Field) {
    setInvalid(field);
    requestAnimationFrame(() => {
      const control = form.current?.elements.namedItem(field);
      if (control instanceof HTMLElement) control.focus();
    });
  }

  async function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (sending.current || cooldown) return;
    setStatus("idle");
    if (!category) return invalidate("category");
    if (!message.trim() || Array.from(message.trim()).length > 2000) return invalidate("message");
    const emailInput = form.current?.elements.namedItem("email");
    if (emailInput instanceof HTMLInputElement && !emailInput.validity.valid) return invalidate("email");

    setInvalid(null);
    sending.current = true;
    try {
      await mutation.mutateAsync();
      setStatus("sent");
      setCategory("");
      setMessage("");
      setEmail("");
    } catch (error) {
      if (error instanceof FeedbackError) {
        const field = fieldFor(error);
        if (field) {
          invalidate(field);
          return;
        }
        if (error.retryAfter > 0) {
          setRetryAfter(error.retryAfter);
          setCooldown(true);
        }
        if (error.status === 429) {
          setStatus("throttled");
          return;
        }
        if (error.code === "SERVICE_OVERLOADED" || error.code === "SERVICE_MAINTENANCE") {
          setStatus("busy");
          return;
        }
        if (error.status >= 400 && error.status < 500) {
          setStatus("rejected");
          return;
        }
      }
      setStatus("unconfirmed");
    } finally {
      sending.current = false;
      mutation.reset();
    }
  }

  const pending = mutation.isPending;
  const guidance = category === "bug"
    ? "Cuéntame qué pasó, qué esperabas que pasara y en qué página o juego lo viste."
    : category === "incorrect_game_information"
      ? "Dime el nombre del juego, qué dato falla y, si la tienes a mano, una fuente para comprobarlo."
      : "Cuéntame lo que te gustaría que supiera. Hasta 2.000 caracteres.";
  const errors = {
    unconfirmed: "No he podido confirmar el envío. Tu mensaje sigue aquí. Si vuelves a intentarlo, podría llegar dos veces.",
    busy: "El canal está ocupado ahora mismo. Tu mensaje sigue aquí. Prueba de nuevo en un rato.",
    throttled: "Se han enviado demasiadas solicitudes en poco tiempo. Tu mensaje sigue aquí. Prueba de nuevo más tarde.",
    rejected: "El envío se ha rechazado. Revisa el mensaje o recarga la página después de copiarlo.",
  };
  const failure = status === "idle" || status === "sent" ? null : errors[status];

  return (
    <div className="page-container community-page">
      <div className="community-reading">
        <header className="community-opening">
          <p className="community-kicker">Te leo</p>
          <HeroTitle id="contact-title" title={title} />
          <p className="community-lead">
            ¿No te mola algo? ¿Te ha petado al intentar poner una nota? ¿Hay datos erróneos? ¿Simplemente quieres decirme lo que te parece la web? Puedes escribirme sin cuenta.
          </p>
        </header>

        <form ref={form} className="feedback-form" aria-labelledby="contact-title" onSubmit={handleSubmit} noValidate>
          <div className="feedback-field">
            <label htmlFor="feedback-category">Motivo</label>
            <select
              id="feedback-category" name="category" required disabled={pending}
              value={category}
              onChange={event => { setCategory(event.target.value as FeedbackCategory | ""); if (invalid === "category") setInvalid(null); }}
              aria-invalid={invalid === "category" || undefined}
              aria-describedby={invalid === "category" ? "feedback-field-error" : undefined}
            >
              <option value="">Elige un motivo</option>
              {Object.entries(categories).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </div>
          <div className="feedback-field">
            <label htmlFor="feedback-message">Mensaje</label>
            <p id="feedback-guidance">{guidance}</p>
            <textarea
              id="feedback-message" name="message" required readOnly={pending}
              rows={6} maxLength={4000} value={message}
              onChange={event => { setMessage(event.target.value); if (invalid === "message") setInvalid(null); }}
              aria-invalid={invalid === "message" || undefined}
              aria-describedby={invalid === "message" ? "feedback-guidance feedback-field-error" : "feedback-guidance"}
            />
          </div>
          <div className="feedback-field">
            <label htmlFor="feedback-email">Correo electrónico <span>(opcional)</span></label>
            <input
              id="feedback-email" name="email" type="email" autoComplete="email"
              readOnly={pending} maxLength={254} value={email}
              onChange={event => { setEmail(event.target.value); if (invalid === "email") setInvalid(null); }}
              aria-invalid={invalid === "email" || undefined}
              aria-describedby={invalid === "email" ? "feedback-field-error" : undefined}
            />
          </div>
          {invalid && <p id="feedback-field-error" role="alert" className="feedback-field-error">{fieldErrors[invalid]}</p>}
          <p className="feedback-privacy">
            Si dejas tu correo, lo usaré para responderte a la mayor brevedad posible. Evita incluir contraseñas o datos sensibles. <Link to="/privacidad">Política de privacidad</Link>.
          </p>
          <button className="button button-primary feedback-submit" type="submit" aria-disabled={pending || cooldown}>
            Enviar mensaje
          </button>
          <div role="status">
            {pending && <p className="notice notice-info">Enviando tu mensaje…</p>}
            {status === "sent" && <p className="notice notice-info">El servicio de correo ha aceptado tu mensaje. Gracias por escribir.</p>}
            {failure && <p className="notice notice-danger">{failure}{cooldown && " El botón se activará cuando puedas reintentar."}</p>}
          </div>
        </form>
      </div>
    </div>
  );
}

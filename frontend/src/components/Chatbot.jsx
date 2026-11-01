import { useEffect, useRef, useState } from "react";
import { preguntarAlAsistente } from "../api/asistente";
import { useAsistente } from "../store/asistenteStore";
import { IconoChat, IconoCerrar, IconoFlecha } from "./Iconos";

const welcome = {
  role: "assistant",
  content: "Hola. Puedo consultar tu saldo, tus cuotas y cómo pagar. Solo leo: no modifico tu deuda.",
};

/**
 * El asistente del deudor. En el computador se abre con la burbuja; en el
 * telefono, con el icono del encabezado (BotonAsistente), y la burbuja no se
 * muestra, porque tapaba los botones para pagar (#303).
 */
export default function Chatbot() {
  const open = useAsistente((s) => s.abierto);
  const alternar = useAsistente((s) => s.alternar);
  const cerrar = useAsistente((s) => s.cerrar);
  const [messages, setMessages] = useState([welcome]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const lista = useRef(null);

  // La conversacion baja sola hasta el ultimo mensaje.
  useEffect(() => {
    const el = lista.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
  }, [messages, busy, open]);

  //  Al salir del portal (cerrar sesion) queda cerrado para la proxima vez.
  useEffect(() => cerrar, [cerrar]);

  //  Esc lo cierra, como cualquier panel.
  useEffect(() => {
    if (!open) return undefined;
    const alTeclear = (e) => { if (e.key === "Escape") cerrar(); };
    window.addEventListener("keydown", alTeclear);
    return () => window.removeEventListener("keydown", alTeclear);
  }, [open, cerrar]);

  async function send(e) {
    e.preventDefault();
    const content = text.trim();
    if (!content || busy) return;
    const next = [...messages, { role: "user", content }];
    setMessages(next);
    setText("");
    setBusy(true);
    try {
      const respuesta = await preguntarAlAsistente(content, next);
      setMessages([...next, { role: "assistant", content: respuesta }]);
    } catch (err) {
      setMessages([...next, { role: "assistant", content: err.message || "El asistente no está disponible." }]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="chat-dock">
      {open ? (
        <div className="chat-panel card" role="dialog" aria-label="Asistente" id="panel-asistente">
          <div className="chat-head">
            <div>
              <strong>Asistente</strong>
              <small>Responde sobre tus deudas y pagos</small>
            </div>
            <button className="btn btn-ghost btn-sm" type="button" onClick={cerrar} aria-label="Cerrar">
              <IconoCerrar size={16} />
            </button>
          </div>
          <div className="messages" ref={lista}>
            {messages.map((m, i) => (
              <div key={i} className={`bubble ${m.role === "user" ? "user" : "bot"}`}>
                {m.content}
              </div>
            ))}
            {busy ? <div className="bubble bot escribiendo" aria-label="Escribiendo"><i /><i /><i /></div> : null}
          </div>
          <form className="composer" onSubmit={send}>
            <input
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="¿Cuánto debo?"
              disabled={busy}
              aria-label="Tu pregunta"
            />
            <button className="btn btn-primary btn-sm" disabled={busy || !text.trim()} aria-label="Enviar">
              <IconoFlecha size={16} />
            </button>
          </form>
        </div>
      ) : null}
      <button className="chat-fab" type="button" onClick={alternar} aria-expanded={open} aria-controls="panel-asistente"
              aria-label={open ? "Cerrar el asistente" : "Abrir el asistente"}>
        {open ? <IconoCerrar key="x" size={22} /> : <IconoChat key="chat" size={24} />}
      </button>
    </div>
  );
}

/** El mismo asistente desde el encabezado del telefono, donde no tapa nada (#303). */
export function BotonAsistente() {
  const open = useAsistente((s) => s.abierto);
  const alternar = useAsistente((s) => s.alternar);
  return (
    <button type="button" className="btn btn-ghost boton-asistente" onClick={alternar} aria-expanded={open}
            aria-controls="panel-asistente" aria-label={open ? "Cerrar el asistente" : "Abrir el asistente"}>
      {open ? <IconoCerrar size={18} /> : <IconoChat size={20} />}
    </button>
  );
}

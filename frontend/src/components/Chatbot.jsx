import { useEffect, useRef, useState } from "react";
import { preguntarAlAsistente } from "../api/asistente";
import { useAsistente } from "../store/asistenteStore";
import { IconoChat, IconoCerrar, IconoFlecha } from "./Iconos";

const welcome = {
  role: "assistant",
  content: "Hola. Puedo consultar tu saldo, tus cuotas y cómo pagar. Solo leo: no modifico tu deuda.",
};

/**
 * El asistente del deudor. Se abre desde arriba (BotonAsistente): en el
 * computador, al comienzo del menu; en el telefono, en el encabezado. No hay
 * burbuja flotante, porque tapaba los botones para pagar (#303). Abierto en el
 * computador, va en una columna a la derecha y la pagina se angosta, asi que
 * tampoco tapa nada (Layout le pone .con-asistente).
 */
export default function Chatbot() {
  const open = useAsistente((s) => s.abierto);
  const alternar = useAsistente((s) => s.alternar);
  const cerrar = useAsistente((s) => s.cerrar);
  const [messages, setMessages] = useState([welcome]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const lista = useRef(null);
  const cerrarBoton = useRef(null);
  const estuvoAbierto = useRef(false);

  // La conversacion baja sola hasta el ultimo mensaje.
  useEffect(() => {
    const el = lista.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
  }, [messages, busy, open]);

  //  Al salir del portal (cerrar sesion) queda cerrado para la proxima vez.
  useEffect(() => cerrar, [cerrar]);

  //  Al abrirlo, el foco entra al panel; al cerrarlo con su X, vuelve al boton que lo abre (el que se
  //  ve: el del menu o el del encabezado). Sin esto el foco quedaba en ninguna parte.
  useEffect(() => {
    if (open) {
      cerrarBoton.current?.focus({ preventScroll: true });
    } else if (estuvoAbierto.current && (!document.activeElement || document.activeElement === document.body)) {
      const boton = [...document.querySelectorAll('[aria-controls="panel-asistente"]')]
        .find((b) => b.getClientRects().length > 0);
      boton?.focus({ preventScroll: true });
    }
    estuvoAbierto.current = open;
  }, [open]);

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
            <button ref={cerrarBoton} className="btn btn-ghost btn-sm" type="button" onClick={cerrar} aria-label="Cerrar">
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
    </div>
  );
}

/**
 * El boton que abre y cierra el asistente, siempre arriba, donde no tapa nada (#303): con texto al
 * comienzo del menu del computador, y solo el icono en el encabezado del telefono.
 */
export function BotonAsistente({ conTexto = false }) {
  const open = useAsistente((s) => s.abierto);
  const alternar = useAsistente((s) => s.alternar);
  const nombre = open ? "Cerrar el asistente" : "Abrir el asistente";
  return (
    <button type="button" className={`btn btn-ghost boton-asistente${conTexto ? " con-texto" : ""}`} onClick={alternar}
            aria-expanded={open} aria-controls="panel-asistente" aria-label={conTexto ? undefined : nombre}>
      {open ? <IconoCerrar size={18} /> : <IconoChat size={20} />}
      {conTexto ? (open ? "Cerrar el asistente" : "Preguntar al asistente") : null}
    </button>
  );
}

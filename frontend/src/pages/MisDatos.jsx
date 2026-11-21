import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { cambiarRecordatorios, misDatos, cambiarTelefono, solicitarCambioCorreo, confirmarCambioCorreo, cancelarCambioCorreo } from "../api/deudas";
import { rutLegible } from "../utils/formato";
import Cargando from "../components/Cargando";

/**
 * Lo que DataBridge sabe del deudor y de parte de quien.
 *
 * Sirve para confirmar que la cobranza es de verdad: si la empresa es la suya
 * y el correo es el suyo, el mensaje que le llego es legitimo. El contacto
 * se corrige aqui; un correo nuevo requiere confirmacion.
 */
export default function MisDatos() {
  const [datos, setDatos] = useState(null);
  const [error, setError] = useState("");
  const [guardando, setGuardando] = useState(false);
  const [edicion, setEdicion] = useState("");
  const [telefono, setTelefono] = useState("");
  const [correo, setCorreo] = useState("");
  const [codigo, setCodigo] = useState("");
  const [pendiente, setPendiente] = useState(null);
  const [contactoOcupado, setContactoOcupado] = useState(false);
  const [mensaje, setMensaje] = useState("");
  const bloqueo = useRef(false);

  async function operar(accion) {
    if (bloqueo.current) return;
    bloqueo.current = true;
    setContactoOcupado(true);
    setError("");
    setMensaje("");
    try { await accion(); }
    catch (err) { setError(err.message); }
    finally { bloqueo.current = false; setContactoOcupado(false); }
  }

  function limpiarContacto() {
    setEdicion(""); setTelefono(""); setCorreo(""); setCodigo(""); setPendiente(null);
  }

  function guardarTelefono(e) {
    e.preventDefault();
    const numero = telefono.replace(/[ ()-]/g, "");
    if (!/^(?:\+?56)?[2-9][0-9]{8}$/.test(numero)) {
      setError("Escribe un teléfono chileno de nueve dígitos, con +56."); return;
    }
    operar(async () => {
      setDatos(await cambiarTelefono(telefono));
      limpiarContacto(); setMensaje("Tu teléfono fue actualizado.");
    });
  }

  function enviarVerificacion(e) {
    e.preventDefault();
    operar(async () => {
      setPendiente(await solicitarCambioCorreo(correo));
      setCorreo(""); setCodigo("");
    });
  }

  function confirmarCorreo(e) {
    e.preventDefault();
    operar(async () => {
      setDatos(await confirmarCambioCorreo(pendiente.solicitudId, codigo));
      limpiarContacto();
      setMensaje("Tu correo fue actualizado. Usa el nuevo correo para volver a entrar cuando termine esta sesión.");
    });
  }

  function cancelarContacto() {
    operar(async () => {
      if (pendiente) await cancelarCambioCorreo(pendiente.solicitudId);
      limpiarContacto();
    });
  }

  useEffect(() => {
    misDatos().then(setDatos).catch((err) => setError(err.message));
  }, []);

  async function recordatorios(quiere) {
    setGuardando(true);
    setError("");
    setDatos((antes) => ({ ...antes, recordatorios: quiere }));
    try {
      setDatos(await cambiarRecordatorios(quiere));
    } catch (err) {
      setDatos((antes) => ({ ...antes, recordatorios: !quiere }));
      setError(err.message);
    } finally {
      setGuardando(false);
    }
  }

  if (!datos) return error ? <div className="error">{error}</div> : <Cargando tarjetas={1} />;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Mis datos</h1>
          <p>Lo que tenemos registrado de ti, y quién nos lo entregó.</p>
        </div>
      </header>
      {error ? <div className="error" role="alert">{error}</div> : null}
      {mensaje ? <p role="status">{mensaje}</p> : null}

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <h2>Tus datos</h2>
          <table className="table">
            <tbody>
              <tr><th>Nombre</th><td>{datos.nombre}</td></tr>
              <tr><th>RUT</th><td>{rutLegible(datos.rut)}</td></tr>
              <tr><th>Correo</th><td>{datos.correo || "Sin correo registrado"}</td></tr>
              <tr><th>Teléfono</th><td>{datos.telefono || "Sin teléfono registrado"}</td></tr>
            </tbody>
          </table>
          {!edicion ? <div className="acciones-fila">
            <button type="button" className="btn btn-ghost btn-sm" disabled={guardando}
              onClick={() => { setEdicion("telefono"); setError(""); setMensaje(""); }}>Cambiar teléfono</button>
            <button type="button" className="btn btn-ghost btn-sm" disabled={guardando}
              onClick={() => { setEdicion("correo"); setError(""); setMensaje(""); }}>Cambiar correo</button>
          </div> : null}
          {edicion === "telefono" ? <form onSubmit={guardarTelefono} aria-busy={contactoOcupado}>
            <div className="field">
              <label htmlFor="nuevo-telefono">Nuevo teléfono</label>
              <input id="nuevo-telefono" type="tel" autoComplete="off" autoFocus maxLength={40}
                value={telefono} onChange={(e) => setTelefono(e.target.value)} required placeholder="+56 9 8765 4321" />
              <p className="hint">Puedes usar un teléfono móvil o fijo de Chile. No enviamos SMS.</p>
            </div>
            <div className="acciones-fila">
              <button className="btn btn-primary btn-sm" disabled={contactoOcupado || !telefono.trim()}>{contactoOcupado ? "Guardando…" : "Guardar teléfono"}</button>
              <button type="button" className="btn btn-ghost btn-sm" disabled={contactoOcupado} onClick={cancelarContacto}>Cancelar</button>
            </div>
          </form> : null}
          {edicion === "correo" ? <form onSubmit={pendiente ? confirmarCorreo : enviarVerificacion} aria-busy={contactoOcupado}>
            {pendiente ? <>
              <p role="status">El correo nuevo todavía no está confirmado. Tu correo anterior sigue vigente.</p>
              <div className="field">
                <label htmlFor="codigo-cambio-correo">Código para confirmar el cambio de correo</label>
                <input key="codigo" id="codigo-cambio-correo" autoFocus autoComplete="off" maxLength={6}
                  value={codigo} onChange={(e) => setCodigo(e.target.value.toUpperCase())} required pattern="[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}" />
                <p className="hint">Escribe los seis caracteres recibidos en el correo nuevo. Vence en 15 minutos y permite cinco intentos. No es tu código de acceso.</p>
              </div>
            </> : <div className="field">
              <label htmlFor="nuevo-correo">Nuevo correo</label>
              <input key="correo" id="nuevo-correo" type="email" autoComplete="off" autoFocus maxLength={254}
                value={correo} onChange={(e) => setCorreo(e.target.value)} required />
              <p className="hint">Enviaremos un código a esa dirección. El cambio se guarda solo al confirmarlo.</p>
            </div>}
            <div className="acciones-fila">
              <button className="btn btn-primary btn-sm" disabled={contactoOcupado}>{contactoOcupado ? "Procesando…" : pendiente ? "Confirmar correo" : "Solicitar código"}</button>
              <button type="button" className="btn btn-ghost btn-sm" disabled={contactoOcupado} onClick={cancelarContacto}>Cancelar</button>
            </div>
          </form> : null}
          <p className="hint">
            El correo y el teléfono se ven a medias para que nadie los lea en tu pantalla. Tú igual puedes
            reconocer si son los tuyos.
          </p>
        </div>

        <div className="card aparece" style={{ "--i": 2 }}>
          <h2>Quién te registró</h2>
          {datos.acreedores.map((a) => (
            <p key={a.rut} style={{ margin: "0 0 12px" }}>
              <b>{a.nombre}</b> (RUT {rutLegible(a.rut)}) te registró con {a.deudas === 1 ? "una deuda" : `${a.deudas} deudas`}
              {a.cobraPorSuCuenta ? <>, y <b>{a.cobraPorSuCuenta}</b> la cobra por su encargo</> : null}.
            </p>
          ))}
          <p className="hint">
            Tus datos llegan inicialmente con la cartera. El correo que confirmes y el teléfono que cambies aquí
            prevalecen sobre cargas posteriores. Si no reconoces la deuda, no pagues y comunícate con tu acreedor.
          </p>
        </div>
      </div>

      <div className="card aparece" style={{ "--i": 3, marginTop: 16 }}>
        <label className="interruptor">
          <input type="checkbox" className="oculto" checked={datos.recordatorios} disabled={guardando || contactoOcupado || !!edicion}
                 onChange={(e) => recordatorios(e.target.checked)} />
          <span className="riel" aria-hidden="true" />
          <span>
            <b>Avisarme por correo {datos.diasAntes} días antes de cada cuota</b>
            <span className="sub">
              El correo trae un código para entrar y la fecha. No trae montos ni enlaces.
            </span>
          </span>
        </label>
      </div>

      <p className="hint">
        ¿Dudas sobre cómo funciona esto? <Link className="link-btn" to="/app/como-funciona">Cómo funciona</Link>
      </p>
    </div>
  );
}

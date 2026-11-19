import { useState } from "react";
import { fecha, fechaHora } from "../utils/formato";

function UltimoIngreso({ valor }) {
  if (!valor) return "Todavía no entra";
  const [dia, ...hora] = fechaHora(valor).split(", ");
  return <time dateTime={valor}>
    <span className="sin-corte">{dia}</span>
    <span className="sub sin-corte">{hora.join(", ")}</span>
  </time>;
}

/**
 * Lo que comparten la ficha de una empresa y el equipo de DataBridge: la lista
 * de personas con su acceso, el formulario para dar de alta a una y la
 * historia de lo que se hizo.
 */

/** Las personas, con lo que se puede hacer con cada una segun los enlaces que trae. */
export function TablaPersonas({ personas, tuCorreo, alCambiar, alCorregir, vacio }) {
  const [ocupada, setOcupada] = useState(null);
  const [editando, setEditando] = useState(null);
  const [nombre, setNombre] = useState("");
  const [correo, setCorreo] = useState("");
  const [error, setError] = useState("");

  function editar(persona) {
    setEditando(persona.id);
    setNombre(persona.nombre);
    setCorreo(persona.correo);
    setError("");
  }

  async function guardar(e) {
    e.preventDefault();
    setOcupada(editando);
    setError("");
    try {
      await alCorregir(editando, { nombre: nombre.trim(), correo: correo.trim() });
      setEditando(null);
    } catch (err) {
      setError(err.message);
    } finally {
      setOcupada(null);
    }
  }

  async function cambiar(persona, accion) {
    setOcupada(persona.id);
    try {
      await alCambiar(persona, accion);
    } finally {
      setOcupada(null);
    }
  }

  if (personas.length === 0) return <div className="empty">{vacio}</div>;

  return (
    <div className="tabla-scroll">
      <table className="table">
        <thead>
          <tr><th>Persona</th><th>Último ingreso</th><th>Acceso</th><th /></tr>
        </thead>
        <tbody>
          {personas.map((p) => (
            <tr key={p.id}>
              <td>
                {p.nombre}{p.correo === tuCorreo ? " (tú)" : ""}
                <span className="sub">{p.correo}</span>
                {editando === p.id ? (
                  <form id={`corregir-persona-${p.id}`} onSubmit={guardar}>
                    {error ? <span className="error" role="alert">{error}</span> : null}
                    <label htmlFor={`nombre-${p.id}`}>Nombre</label>
                    <input id={`nombre-${p.id}`} value={nombre} maxLength={160} required
                      onChange={(e) => setNombre(e.target.value)} />
                    <label htmlFor={`correo-${p.id}`}>Correo</label>
                    <input id={`correo-${p.id}`} type="email" value={correo} maxLength={254} required
                      onChange={(e) => setCorreo(e.target.value)} />
                    <span className="sub">Cambiar el correo cierra sus sesiones y anula enlaces y códigos pendientes.</span>
                  </form>
                ) : null}
              </td>
              <td><UltimoIngreso valor={p.ultimoAcceso} /></td>
              <td>
                {editando === p.id ? (
                  <>
                    <button type="submit" form={`corregir-persona-${p.id}`} className="btn btn-primary btn-sm"
                      disabled={ocupada === p.id}>Guardar</button>
                    <button type="button" className="btn btn-ghost btn-sm"
                      onClick={() => setEditando(null)}>Cancelar</button>
                  </>
                ) : <button type="button" className="btn btn-ghost btn-sm" onClick={() => editar(p)}
                    disabled={ocupada !== null}>Corregir</button>}
                {p.habilitada
                  ? <span className="badge badge-ok">Con acceso</span>
                  : <span className="badge badge-muted">Sin acceso</span>}
                {!p.habilitada && p.deshabilitadaEl ? <span className="sub">desde el {fecha(p.deshabilitadaEl)}</span> : null}
              </td>
              <td>
                {p._links?.deshabilitar ? (
                  <button type="button" className="btn btn-peligro btn-sm" disabled={ocupada === p.id}
                          onClick={() => cambiar(p, "quitar")}>
                    Quitar acceso
                  </button>
                ) : null}
                {p._links?.habilitar ? (
                  <button type="button" className="btn btn-ghost btn-sm" disabled={ocupada === p.id}
                          onClick={() => cambiar(p, "devolver")}>
                    Devolver acceso
                  </button>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Nombre y correo de una persona nueva. `alGuardar` recibe { nombre, correo } y lanza si no se pudo. */
export function FormPersona({ prefijo, boton, alGuardar, ejemplo = "nombre@empresa.cl" }) {
  const [nombre, setNombre] = useState("");
  const [correo, setCorreo] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function guardar(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await alGuardar({ nombre: nombre.trim(), correo: correo.trim() });
      setNombre("");
      setCorreo("");
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={guardar}>
      {error ? <div className="error" role="alert">{error}</div> : null}
      <div className="field">
        <label htmlFor={`${prefijo}-nombre`}>Nombre</label>
        <input id={`${prefijo}-nombre`} value={nombre} maxLength={160} autoComplete="off"
               onChange={(e) => setNombre(e.target.value)} placeholder="Ana Soto" required />
      </div>
      <div className="field">
        <label htmlFor={`${prefijo}-correo`}>Correo</label>
        <input id={`${prefijo}-correo`} type="email" value={correo} maxLength={254} autoComplete="off"
               onChange={(e) => setCorreo(e.target.value)} placeholder={ejemplo} required />
      </div>
      <button className="btn btn-primary btn-block" disabled={busy || !nombre.trim() || !correo.trim()}>
        {busy ? <><span className="girando" /> Guardando…</> : boton}
      </button>
    </form>
  );
}

const QUE_SE_HIZO = {
  empresa_creada: () => "Dio de alta a la empresa",
  empresa_suspendida: () => "Suspendió a la empresa",
  empresa_reactivada: () => "Reactivó a la empresa",
  empresa_corregida: () => "Corrigió los nombres de la empresa",
  persona_corregida: (a) => `Corrigió los datos de ${a.sobre}`,
  persona_creada: (a) => `Le dio acceso a ${a.sobre}`,
  admin_creado: (a) => `Dio de alta como admin a ${a.sobre}`,
  persona_deshabilitada: (a) => `Le quitó el acceso a ${a.sobre}`,
  persona_habilitada: (a) => `Le devolvió el acceso a ${a.sobre}`,
  aviso_reintentado: (a) => `Reintentó ${a.sobre}`,
  suscripcion_activada: (a) => `Activó ${a.sobre}`,
  suscripcion_desactivada: (a) => `Desactivó ${a.sobre}`,
  suscripcion_secreto_cambiado: (a) => `Le cambió el secreto a ${a.sobre}`,
  suscripcion_probada: (a) => `Probó ${a.sobre}`,
};

const NOMBRES_CAMPO = { nombre: "Nombre", correo: "Correo", razonSocial: "Razón social" };
function Cambios({ accion }) {
  if (!accion.anteriores || !accion.nuevos) return null;
  try {
    const anteriores = JSON.parse(accion.anteriores);
    const nuevos = JSON.parse(accion.nuevos);
    return <div className="sub">{Object.keys(nuevos).map((campo) => (
      <div key={campo}>{NOMBRES_CAMPO[campo] || campo}: {anteriores[campo]} → {nuevos[campo]}</div>
    ))}</div>;
  } catch {
    return null;
  }
}

/** Lo que se hizo desde el panel, quien y cuando. Lo mas reciente primero. */
export function Historial({ acciones }) {
  if (acciones.length === 0) return <div className="empty">Todavía no se ha hecho nada desde el panel.</div>;
  return (
    <div className="lista">
      {acciones.map((a, i) => (
        <div className="fila" key={`${a.accion}-${a.cuando}-${i}`}>
          <div className="fila-que">
            <b>{(QUE_SE_HIZO[a.accion] || (() => a.accion))(a)}</b>
            <span>{a.quien} · {fechaHora(a.cuando)}</span>
            <Cambios accion={a} />
          </div>
        </div>
      ))}
    </div>
  );
}

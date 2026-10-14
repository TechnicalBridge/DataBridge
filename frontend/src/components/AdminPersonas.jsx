import { useState } from "react";
import { fecha, fechaHora } from "../utils/formato";

/**
 * Lo que comparten la ficha de una empresa y el equipo de DataBridge: la lista
 * de personas con su acceso, el formulario para dar de alta a una y la
 * historia de lo que se hizo.
 */

/** Las personas, con lo que se puede hacer con cada una segun los enlaces que trae. */
export function TablaPersonas({ personas, tuCorreo, alCambiar, vacio }) {
  const [ocupada, setOcupada] = useState(null);

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
              </td>
              <td>{p.ultimoAcceso ? fechaHora(p.ultimoAcceso) : "Todavía no entra"}</td>
              <td>
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
  persona_creada: (a) => `Le dio acceso a ${a.sobre}`,
  admin_creado: (a) => `Dio de alta como admin a ${a.sobre}`,
  persona_deshabilitada: (a) => `Le quitó el acceso a ${a.sobre}`,
  persona_habilitada: (a) => `Le devolvió el acceso a ${a.sobre}`,
};

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
          </div>
        </div>
      ))}
    </div>
  );
}

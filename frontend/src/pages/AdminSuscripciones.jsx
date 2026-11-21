import { useEffect, useState } from "react";
import { activarSuscripcion, cambiarSecretoDeSuscripcion, desactivarSuscripcion, probarSuscripcion, suscripciones } from "../api/admin";
import Cargando from "../components/Cargando";
import { fechaHora } from "../utils/formato";

/** Lo que respondio el receptor a la prueba, en una frase. */
function resultadoDePrueba(r) {
  if (r.respondio) return { ok: true, texto: `Respondió ${r.estado} en ${r.milisegundos} ms: recibe los avisos.` };
  if (r.estado) return { ok: false, texto: `Respondió ${r.estado}${r.detalle ? `: ${r.detalle}` : ""}.` };
  return { ok: false, texto: `No respondió${r.detalle ? `: ${r.detalle}` : ""}.` };
}

/**
 * Las suscripciones de avisos de las empresas (#438): a donde les avisa
 * DataBridge, como van sus entregas, y activarlas, desactivarlas, cambiarles
 * el secreto o probarlas sin SQL. Cada accion queda en la historia de la
 * empresa. El secreto solo se ve una vez, al cambiarlo.
 */
export default function AdminSuscripciones() {
  const [lista, setLista] = useState(null);
  const [error, setError] = useState("");
  const [vuelta, setVuelta] = useState(0);
  //  Lo que paso en cada suscripcion: la prueba, el secreto nuevo o un error. Por id.
  const [resultados, setResultados] = useState({});
  const [ocupada, setOcupada] = useState(null);

  useEffect(() => {
    setError("");
    suscripciones().then(setLista).catch((e) => setError(e.message));
  }, [vuelta]);

  const anotar = (id, valor) => setResultados((r) => ({ ...r, [id]: valor }));

  async function hacer(s, accion) {
    if (accion === "desactivar" && !window.confirm(`¿Desactivar los avisos a ${s.empresa}? No se le encolan avisos nuevos, `
      + "y los pendientes esperan hasta que la actives. Queda en la bitácora del panel.")) return;
    if (accion === "secreto" && !window.confirm(`¿Cambiar el secreto de ${s.empresa}? Desde ahora los avisos van firmados `
      + "con el nuevo, así que la empresa tiene que ponerlo en su sistema. Queda en la bitácora del panel.")) return;
    setOcupada(`${s.id}-${accion}`);
    anotar(s.id, null);
    try {
      if (accion === "probar") anotar(s.id, { prueba: resultadoDePrueba(await probarSuscripcion(s.id)) });
      if (accion === "secreto") anotar(s.id, { secreto: (await cambiarSecretoDeSuscripcion(s.id)).secreto });
      if (accion === "activar") await activarSuscripcion(s.id);
      if (accion === "desactivar") await desactivarSuscripcion(s.id);
      if (accion === "activar" || accion === "desactivar") setVuelta((v) => v + 1);
    } catch (e) {
      anotar(s.id, { error: e.message });
    } finally {
      setOcupada(null);
    }
  }

  async function copiar(texto) {
    try {
      await navigator.clipboard.writeText(texto);
    } catch {
      //  Sin permiso para el portapapeles, queda a la vista para copiarlo a mano.
    }
  }

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Suscripciones</h1>
          <p>
            A dónde le avisa DataBridge los eventos a cada empresa. Desactivar pausa los avisos: no se encolan nuevos y
            los pendientes esperan hasta que se active.
          </p>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVuelta((v) => v + 1)}>Actualizar</button>
      </header>

      <div className="card aparece" style={{ "--i": 1 }}>
        {error ? <div className="error" role="alert">{error}</div> : null}
        {!lista && !error ? (
          <Cargando tarjetas={1} />
        ) : lista && lista.length === 0 ? (
          <div className="empty">
            Ninguna empresa tiene suscripciones: se crean por la API de integración, que está apagada.
          </div>
        ) : lista ? (
          <div className="tabla-scroll">
            <table className="table tabla-suscripciones">
              <caption className="hint">{lista.length} {lista.length === 1 ? "suscripción" : "suscripciones"}</caption>
              <thead>
                <tr><th scope="col">Suscripción</th><th scope="col">Estado</th></tr>
              </thead>
              <tbody>
                {lista.map((s) => {
                  const r = resultados[s.id];
                  return (
                    <tr key={s.id}>
                      <td>
                        <b>{s.empresa}</b>
                        <span className="sub mono">{s.url}</span>
                        <span className="sub">
                          {s.eventos.length ? s.eventos.join(", ") : "Todos los eventos"}
                        </span>
                        {r?.prueba ? (
                          <div className={r.prueba.ok ? "notice" : "aviso"} role="status">{r.prueba.texto}</div>
                        ) : null}
                        {r?.secreto ? (
                          <div className="notice secreto-nuevo" role="status">
                            <b>El secreto nuevo, que no se vuelve a mostrar:</b>
                            <code>{r.secreto}</code>
                            <span>La empresa tiene que ponerlo en su sistema: desde ahora los avisos van firmados con él.</span>
                            <div className="acciones-fila">
                              <button type="button" className="btn btn-ghost btn-sm" onClick={() => copiar(r.secreto)}>Copiar</button>
                              <button type="button" className="btn btn-ghost btn-sm" onClick={() => anotar(s.id, null)}>Ya lo guardé</button>
                            </div>
                          </div>
                        ) : null}
                        {r?.error ? <div className="error" role="alert">{r.error}</div> : null}
                      </td>
                      <td>
                        <span className={`badge ${s.activa ? "badge-ok" : "badge-muted"}`}>{s.activa ? "Activa" : "Desactivada"}</span>
                        <span className="sub">
                          {s.ultimaEntrega ? `Última entrega: ${fechaHora(s.ultimaEntrega)}` : "Sin entregas"}
                        </span>
                        {s.pendientes || s.fallidas ? (
                          <span className="sub">
                            {s.pendientes ? `${s.pendientes} ${s.pendientes === 1 ? "pendiente" : "pendientes"}` : ""}
                            {s.pendientes && s.fallidas ? " · " : ""}
                            {s.fallidas ? `${s.fallidas} ${s.fallidas === 1 ? "fallida" : "fallidas"} (en Operación)` : ""}
                          </span>
                        ) : null}
                        <div className="acciones-fila">
                          <button type="button" className="btn btn-ghost btn-sm" disabled={!!ocupada}
                                  onClick={() => hacer(s, "probar")}>
                            {ocupada === `${s.id}-probar` ? "Probando…" : "Probar"}
                          </button>
                          <button type="button" className="btn btn-ghost btn-sm" disabled={!!ocupada}
                                  onClick={() => hacer(s, "secreto")}>
                            Cambiar secreto
                          </button>
                          {s.activa ? (
                            <button type="button" className="btn btn-peligro btn-sm" disabled={!!ocupada}
                                    onClick={() => hacer(s, "desactivar")}>
                              Desactivar
                            </button>
                          ) : (
                            <button type="button" className="btn btn-soft btn-sm" disabled={!!ocupada}
                                    onClick={() => hacer(s, "activar")}>
                              Activar
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : null}
      </div>
    </div>
  );
}

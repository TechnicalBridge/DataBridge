import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { cerrarSesion, listarEmpresas, sesionesAbiertas } from "../api/admin";
import Cargando from "../components/Cargando";
import Paginas from "../components/Paginas";
import { fechaHora, hora, rutLegible } from "../utils/formato";

const POR_PAGINA = 20;

/**
 * Las sesiones abiertas del personal de las empresas y de DataBridge (#439),
 * para cortar el acceso de alguien que deja su empresa o que perdio su equipo.
 * Cerrar una corta su renovacion; el acceso que la persona ya tiene dura hasta
 * que vence, a lo mas unos minutos (#428), y la pantalla lo dice antes y despues.
 * Las sesiones de los deudores no salen aqui.
 */
export default function AdminSesiones() {
  const [params, setParams] = useSearchParams();
  const pagina = Math.max(0, Number(params.get("pagina")) || 0);
  const [datos, setDatos] = useState(null);
  const [empresas, setEmpresas] = useState([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState("");
  const [vuelta, setVuelta] = useState(0);
  const [ocupada, setOcupada] = useState(null);
  const [cerrada, setCerrada] = useState(null);
  const [fallo, setFallo] = useState(null);
  //  Si se cambia de pagina mientras llega la respuesta anterior, esa se descarta.
  const ultima = useRef(0);

  useEffect(() => {
    listarEmpresas().then(setEmpresas).catch(() => setEmpresas([]));
  }, []);

  useEffect(() => {
    const esta = ++ultima.current;
    setCargando(true);
    setError("");
    sesionesAbiertas({ pagina, porPagina: POR_PAGINA })
      .then((r) => { if (esta === ultima.current) setDatos(r); })
      .catch((e) => { if (esta === ultima.current) setError(e.message); })
      .finally(() => { if (esta === ultima.current) setCargando(false); });
  }, [pagina, vuelta]);

  //  Si ms-debt no responde, la empresa se ve por su RUT.
  const empresaDe = (s) => (s.rol === "admin" ? "DataBridge"
    : empresas.find((e) => e.rut === s.empresaRut)?.nombre || (s.empresaRut ? rutLegible(s.empresaRut) : "Sin empresa"));
  const quien = (s) => s.nombre || s.correo;

  async function cerrar(s) {
    const tuya = s.tuya ? "Es una sesión tuya: si es la de este navegador, tendrás que volver a entrar. " : "";
    if (!window.confirm(`¿Cerrar la sesión de ${quien(s)}? ${tuya}No podrá renovarla y tendrá que volver a entrar. `
      + `Su acceso termina a más tardar en ${s.minutosDeAcceso} minutos, cuando vence el que ya tiene. `
      + "Queda en la bitácora del panel.")) return;
    setOcupada(s.familia);
    setCerrada(null);
    setFallo(null);
    try {
      const r = await cerrarSesion(s.familia);
      setCerrada({ quien: quien(s), hasta: r.accesoHasta });
      setVuelta((v) => v + 1);
    } catch (e) {
      setFallo({ familia: s.familia, texto: e.message });
    } finally {
      setOcupada(null);
    }
  }

  const irA = (n) => setParams(n > 0 ? { pagina: String(n) } : {});
  const filas = datos?.filas || [];
  const total = datos?.pagina?.totalElements ?? filas.length;
  const minutos = filas[0]?.minutosDeAcceso;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Sesiones abiertas</h1>
          <p>
            Las sesiones del personal de las empresas y de DataBridge que todavía se pueden renovar. Cerrar una corta
            su renovación: la persona tiene que volver a entrar, y el acceso que ya tiene dura hasta que vence
            {minutos ? `, a lo más ${minutos} minutos` : ""}. Las de los deudores no salen aquí.
          </p>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVuelta((v) => v + 1)}>Actualizar</button>
      </header>

      {cerrada ? (
        <div className="notice aparece" role="status">
          Se cerró la sesión de {cerrada.quien}: su acceso termina a más tardar a las {hora(cerrada.hasta)}, cuando
          vence el que ya tiene.
        </div>
      ) : null}

      <div className="card aparece" style={{ "--i": 1 }}>
        {error ? <div className="error" role="alert">{error}</div> : null}
        {cargando && !datos && !error ? (
          <Cargando tarjetas={1} />
        ) : datos && filas.length === 0 ? (
          <div className="empty">Nadie del personal tiene una sesión abierta.</div>
        ) : datos ? (
          <>
            <div className="tabla-scroll">
              <table className="table tabla-sesiones">
                <caption className="hint">{total} {total === 1 ? "sesión abierta" : "sesiones abiertas"}</caption>
                <thead>
                  <tr><th scope="col">Persona</th><th scope="col">Sesión</th></tr>
                </thead>
                <tbody>
                  {filas.map((s) => (
                    <tr key={s.familia}>
                      <td>
                        <b>{quien(s)}</b>
                        {s.tuya ? <span className="badge badge-muted">Tu sesión</span> : null}
                        {s.nombre ? <span className="sub">{s.correo}</span> : null}
                        <span className="sub">{empresaDe(s)} · {s.rol === "admin" ? "admin" : "usuario"}</span>
                      </td>
                      <td>
                        <span className="sub">Entró el {fechaHora(s.desde)}</span>
                        <span className="sub">{s.renovada ? `Renovó el ${fechaHora(s.renovada)}` : "Todavía no la renueva"}</span>
                        <span className="sub">Puede renovarla hasta el {fechaHora(s.vence)}</span>
                        {fallo?.familia === s.familia ? <div className="error" role="alert">{fallo.texto}</div> : null}
                        <div className="acciones-fila">
                          <button type="button" className="btn btn-peligro btn-sm" disabled={!!ocupada}
                                  onClick={() => cerrar(s)}>
                            {ocupada === s.familia ? "Cerrando…" : "Cerrar sesión"}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Paginas total={total} porPagina={POR_PAGINA} pagina={pagina} alCambiar={irA} etiqueta="Páginas de sesiones" />
          </>
        ) : null}
      </div>
    </div>
  );
}

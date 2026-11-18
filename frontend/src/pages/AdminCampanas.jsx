import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { campanasAdmin, resumenDeDeudas } from "../api/admin";

const ESTADOS = {
  open: "Abiertas", repacted: "En convenio", paid: "Pagadas",
  withdrawn: "Retiradas", disputed: "Disputadas",
};
const CAMPANA = { running: "En curso", paused: "Pausada", finished: "Terminada" };
const numero = (valor) => Number(valor).toLocaleString("es-CL");

/** Vista global del administrador: totales sin fichas ni identidad de deudores. */
export default function AdminCampanas() {
  const [resumen, setResumen] = useState(null);
  const [campanas, setCampanas] = useState(null);
  useEffect(() => {
    resumenDeDeudas().then(setResumen).catch(() => setResumen("error"));
    campanasAdmin().then(setCampanas).catch(() => setCampanas("error"));
  }, []);

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Campañas y deudas</h1>
          <p>Totales de toda la plataforma, sin información de deudores.</p>
        </div>
      </header>
      <section className="card" style={{ marginBottom: 16 }}>
        <h2>Deudas por estado</h2>
        {resumen === "error" ? <p role="alert">No se pudo consultar el resumen de deudas.</p>
          : !resumen ? <p>Consultando cifras…</p>
            : <div className="admin-cifras-compactas">
              {Object.entries(ESTADOS).map(([clave, nombre]) => (
                <div key={clave}><b>{numero(resumen.deudasPorEstado[clave] ?? 0)}</b><span>{nombre}</span></div>
              ))}
            </div>}
      </section>
      <section className="card">
        <h2>Campañas registradas</h2>
        {campanas === "error" ? <p role="alert">No se pudo consultar las campañas.</p>
          : !campanas ? <p>Consultando campañas…</p>
            : campanas.length === 0 ? <p>Todavía no hay campañas.</p>
              : <div className="tabla-scroll">
                <table className="table">
                  <thead><tr><th>Campaña</th><th>Empresa gestora</th><th>Estado</th><th className="num">Deudas</th></tr></thead>
                  <tbody>
                    {campanas.map((c) => (
                      <tr key={c.id}>
                        <td>{c.nombre}</td><td>{c.empresa}</td>
                        <td>{CAMPANA[c.estado] ?? c.estado}</td><td className="num">{numero(c.deudas)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>}
        <p className="hint">Las deudas sin campaña están incluidas en el total por estado, no en la tabla de campañas.</p>
        <Link to="/admin/empresas">Ver empresas y comisiones</Link>
      </section>
    </div>
  );
}

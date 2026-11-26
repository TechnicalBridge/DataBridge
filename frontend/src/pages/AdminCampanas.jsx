import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { campanasAdmin, estadoDelContacto, resumenDeDeudas } from "../api/admin";
import Paginas, { dePagina } from "../components/Paginas";
import { ESTADO_DEUDA, fecha, fechaHora, hora, hoyEnChile } from "../utils/formato";

const CAMPANA = {
  running: { nombre: "En curso", clase: "badge-ok" },
  paused: { nombre: "Pausada", clase: "badge-wait" },
  finished: { nombre: "Terminada", clase: "badge-muted" },
};
const numero = (valor) => Number(valor).toLocaleString("es-CL");
const de = (n, uno, varios) => `${numero(n)} ${n === 1 ? uno : varios}`;
const POR_PAGINA = 10;

/** Cierra una frase sin repetir el punto de «p. m.». */
const conPunto = (texto) => (texto.endsWith(".") ? texto : `${texto}.`);

/** Hoy o con su dia, segun cuando fue. */
const cuando = (iso) => (iso && new Date(iso).toLocaleDateString("sv-SE", { timeZone: "America/Santiago" }) === hoyEnChile()
  ? `a las ${hora(iso)}` : `el ${fechaHora(iso)}`);

/**
 * Una campana en curso, con deudas abiertas y que ya empezo, a cuyos deudores
 * no les llego nada en siete dias: puede ser la ley o deudores sin correo, pero
 * tambien una campana detenida (#441).
 */
function sinContactos(c) {
  return c.estado === "running" && c.deudasAbiertas > 0 && c.contactosSemana === 0 && c.desde < hoyEnChile();
}

function TablaCampanas({ filas, etiqueta }) {
  const [pagina, setPagina] = useState(0);
  const { enPagina, filas: visibles } = dePagina(filas, pagina, POR_PAGINA);
  return (
    <>
      <div className="tabla-scroll">
        <table className="table tabla-campanas-admin">
          <thead><tr><th scope="col">Campaña</th><th scope="col">Deudas y contactos</th></tr></thead>
          <tbody>
            {visibles.map((c) => {
              const estado = CAMPANA[c.estado] || { nombre: c.estado, clase: "badge-muted" };
              return (
                <tr key={c.id}>
                  <td>
                    <b>{c.nombre}</b>
                    <span className="sub">
                      {c.empresa === c.acreedor ? `${c.empresa}, que cobra lo suyo` : `${c.empresa}, para ${c.acreedor}`}
                    </span>
                    <span className="sub">
                      Desde el {fecha(c.desde)}{c.hasta ? ` hasta el ${fecha(c.hasta)}` : ", sin fecha de término"}
                    </span>
                  </td>
                  <td>
                    <span className={`badge ${estado.clase}`}>{estado.nombre}</span>
                    {sinContactos(c) ? <span className="badge badge-warn">Sin contactos en 7 días</span> : null}
                    <span className="sub">{de(c.deudas, "deuda", "deudas")}, {de(c.deudasAbiertas, "abierta", "abiertas")}</span>
                    <span className="sub">Correos: {numero(c.contactosHoy)} hoy, {numero(c.contactosSemana)} en 7 días</span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <Paginas total={filas.length} porPagina={POR_PAGINA} pagina={enPagina}
        alCambiar={setPagina} etiqueta={etiqueta} />
    </>
  );
}

/** La ultima pasada de contacto, la proxima, si se detuvo y si la ley esta activa (#441). */
function PasadaDeContacto({ estado }) {
  if (estado === "error") return <p role="alert">No se pudo consultar la pasada de contacto: ms-debt no respondió.</p>;
  if (!estado) return <p>Consultando la pasada de contacto…</p>;
  const u = estado.ultima;
  return (
    <>
      {estado.atrasada ? (
        <div className="error" role="alert">
          La pasada que tocaba después de {u ? `la última, ${cuando(u.inicio)},` : "arrancar ms-debt"} no corrió: la
          tarea que les escribe a los deudores puede estar detenida. Hay que revisar el registro de ms-debt.
        </div>
      ) : null}
      {!u ? (
        <p>Todavía no ha pasado ninguna desde que arrancó ms-debt.</p>
      ) : u.error ? (
        <div className="aviso" role="status">
          La última pasada, {cuando(u.inicio)}, se cayó con un error ({u.error}): no les escribió a los deudores.
          Si sigue cayéndose, hay que revisar el registro de ms-debt.
        </div>
      ) : !u.enHorario ? (
        <p>La última pasada, {cuando(u.inicio)}, fue fuera del horario de cobranza: no salió nada, y está bien.</p>
      ) : (
        <p>
          La última pasada, {cuando(u.inicio)}: {de(u.enviados, "toque de campaña", "toques de campaña")},{" "}
          {de(u.invitados, "invitación", "invitaciones")} y {de(u.pendientes, "pendiente", "pendientes")} para la próxima.
        </p>
      )}
      {estado.enCursoDesde ? <p>{conPunto(`Hay una pasada corriendo desde ${cuando(estado.enCursoDesde).replace(/^a las/, "las")}`)}</p> : null}
      <p>{conPunto(`La próxima toca ${cuando(estado.proxima)}`)}</p>
      <div className="acciones-fila">
        <span className={`badge ${estado.horarioLegal ? "badge-ok" : "badge-peligro"}`}>
          Horario de cobranza: {estado.horarioLegal ? "activo" : "apagado"}
        </span>
        <span className={`badge ${estado.limiteSemanal ? "badge-ok" : "badge-peligro"}`}>
          Límite de 2 correos por semana: {estado.limiteSemanal ? "activo" : "apagado"}
        </span>
      </div>
      {!estado.horarioLegal || !estado.limiteSemanal ? (
        <p className="hint">
          Apagados con <code>CONTACTO_SIN_HORARIO</code> o <code>CONTACTO_SIN_LIMITE</code>: es solo para probar, nunca
          con deudores reales.
        </p>
      ) : null}
      <p className="hint">Se cuenta desde que arrancó ms-debt ({cuando(estado.desde)}); al reiniciarlo se empieza de nuevo.</p>
    </>
  );
}

/** Vista global del administrador: totales sin fichas ni identidad de deudores. */
export default function AdminCampanas() {
  const [resumen, setResumen] = useState(null);
  const [campanas, setCampanas] = useState(null);
  const [contacto, setContacto] = useState(null);
  useEffect(() => {
    resumenDeDeudas().then(setResumen).catch(() => setResumen("error"));
    campanasAdmin().then(setCampanas).catch(() => setCampanas("error"));
    estadoDelContacto().then(setContacto).catch(() => setContacto("error"));
  }, []);

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Campañas y deudas</h1>
          <p>Las campañas de todas las empresas y cómo va la pasada que les escribe a los deudores, sin información de deudores.</p>
        </div>
      </header>
      <section className="card" style={{ marginBottom: 16 }}>
        <h2>La pasada de contacto</h2>
        <PasadaDeContacto estado={contacto} />
      </section>
      <section className="card" style={{ marginBottom: 16 }}>
        <h2>Campañas registradas</h2>
        {campanas === "error" ? <p role="alert">No se pudo consultar las campañas.</p>
          : !campanas ? <p>Consultando campañas…</p>
            : campanas.length === 0 ? <p>Todavía no hay campañas.</p>
              : <>
                {campanas.some((c) => c.estado !== "finished") &&
                  <TablaCampanas filas={campanas.filter((c) => c.estado !== "finished")} etiqueta="Campañas en curso" />}
                {campanas.some((c) => c.estado === "finished") &&
                  <details className="terminadas">
                    <summary>Terminadas <span className="chip-cuenta">{numero(campanas.filter((c) => c.estado === "finished").length)}</span></summary>
                    <TablaCampanas filas={campanas.filter((c) => c.estado === "finished")} etiqueta="Campañas terminadas" />
                  </details>}
              </>}
        <p className="hint">
          Los correos cuentan todo lo que les llegó a los deudores de cada campaña (la invitación, los toques, los
          recordatorios y los reenvíos), como los cuenta el límite de la ley.
        </p>
      </section>
      <section className="card">
        <h2>Deudas por estado</h2>
        {resumen === "error" ? <p role="alert">No se pudo consultar el resumen de deudas.</p>
          : !resumen ? <p>Consultando cifras…</p>
            : <div className="admin-cifras-compactas">
              {Object.entries(ESTADO_DEUDA).map(([clave, nombre]) => (
                <div key={clave}><b>{numero(resumen.deudasPorEstado[clave] ?? 0)}</b><span>{nombre}</span></div>
              ))}
            </div>}
        <p className="hint">Las deudas sin campaña están incluidas en el total por estado, no en la tabla de campañas.</p>
        <Link to="/admin/empresas">Ver empresas y comisiones</Link>
      </section>
    </div>
  );
}

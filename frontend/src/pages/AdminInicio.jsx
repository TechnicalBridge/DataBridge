import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { SERVICIOS, estadoDe, resumenDeDeudas, resumenDePagos } from "../api/admin";
import { useAuth } from "../store/authStore";
import { IconoAlerta, IconoCheck } from "../components/Iconos";
import { dinero } from "../utils/formato";

const ESTADOS_DEUDA = {
  open: "Abiertas", repacted: "En convenio", paid: "Pagadas",
  withdrawn: "Retiradas", disputed: "Disputadas",
};
const PASARELAS = { mercadopago: "Mercado Pago", khipu: "Khipu", webpay: "Webpay" };
const numero = (n) => Number(n).toLocaleString("es-CL");

/**
 * La portada del panel de administracion: quien entro y si cada servicio le
 * responde. Es lo primero que ve el admin de DataBridge, y la prueba de que su
 * sesion llega a los tres servicios.
 */
export default function AdminInicio() {
  const { user } = useAuth();
  //  Por servicio: sin valor mientras se consulta, y despues "ok" o "caido".
  const [estados, setEstados] = useState({});
  const [cifras, setCifras] = useState({});

  function consultar() {
    setEstados({});
    setCifras({});
    SERVICIOS.forEach(({ tramo }) => {
      estadoDe(tramo)
        .then((r) => setEstados((e) => ({ ...e, [tramo]: r.ok ? "ok" : "caido" })))
        .catch(() => setEstados((e) => ({ ...e, [tramo]: "caido" })));
    });
    resumenDeDeudas()
      .then((r) => setCifras((c) => ({ ...c, debt: r })))
      .catch(() => setCifras((c) => ({ ...c, debt: "error" })));
    resumenDePagos()
      .then((r) => setCifras((c) => ({ ...c, payments: r })))
      .catch(() => setCifras((c) => ({ ...c, payments: "error" })));
  }

  useEffect(consultar, []);

  const consultando = SERVICIOS.some(({ tramo }) => !estados[tramo]);
  const caidos = SERVICIOS.filter(({ tramo }) => estados[tramo] === "caido").length;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Panel de administración</h1>
          <p>Hola, {user?.nombre}. Desde aquí el equipo de DataBridge opera la plataforma.</p>
        </div>
      </header>

      <section className="admin-cifras" aria-label="Cifras de la plataforma">
        <div className="card">
          <h2>Empresas</h2>
          <Cifra dato={cifras.debt} render={(r) => (
            <>
              <Link to="/admin/empresas" className="admin-cifra-enlace">{numero(r.empresasActivas)} activas</Link>
              <Link to="/admin/empresas" className="admin-cifra-enlace">{numero(r.empresasSuspendidas)} suspendidas</Link>
            </>
          )} />
        </div>
        <div className="card">
          <h2>Deudas por estado</h2>
          <Cifra dato={cifras.debt} render={(r) => (
            <div className="admin-cifra-lista">
              {Object.entries(ESTADOS_DEUDA).map(([estado, etiqueta]) => (
                <Link key={estado} to="/admin/campanas" className="admin-cifra-enlace">
                  {etiqueta}: {numero(r.deudasPorEstado[estado] ?? 0)}
                </Link>
              ))}
            </div>
          )} />
        </div>
        <div className="card">
          <h2>Este mes · recuperación</h2>
          <Cifra dato={cifras.debt} render={(r) => (
            <>
              <Link to="/admin/empresas" className="admin-cifra-enlace">Recuperado: {dinero(r.recuperado)}</Link>
              <Link to="/admin/empresas" className="admin-cifra-enlace">Comisión DataBridge: {dinero(r.comision)}</Link>
              <p className="hint">Recuperado incluye el monto cobrado con intereses; la comisión usa la misma base por empresa.</p>
            </>
          )} />
        </div>
        <div className="card">
          <h2>Pagos de hoy</h2>
          <Cifra dato={cifras.payments} render={(r) => (
            <>
              <Link to="/admin/operacion" className="admin-cifra-enlace">
                {numero(Object.values(r.pagosHoy).reduce((a, b) => a + b, 0))} confirmados
              </Link>
              <div className="admin-cifra-lista">
                {Object.entries(PASARELAS).map(([clave, nombre]) => (
                  <Link key={clave} to="/admin/operacion" className="admin-cifra-enlace">
                    {nombre}: {numero(r.pagosHoy[clave] ?? 0)} hoy · {numero(r.pagosMes[clave] ?? 0)} este mes
                  </Link>
                ))}
              </div>
              <Link to="/admin/operacion" className="admin-cifra-enlace">
                Abiertos más de una hora: {numero(r.abiertosMasDeUnaHora)}
              </Link>
              <p className="hint">Este mes: {numero(Object.values(r.pagosMes).reduce((a, b) => a + b, 0))} pagos.</p>
            </>
          )} />
        </div>
      </section>

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <h2>Servicios</h2>
          <div className="lista">
            {SERVICIOS.map(({ tramo, nombre, servicio, que }, i) => (
              <div className="fila" key={tramo} style={{ "--i": i }}>
                {estados[tramo] === "caido" ? <IconoAlerta size={20} /> : <IconoCheck size={20} />}
                <div className="fila-que">
                  <b>{nombre}</b>
                  <span>{servicio} · {que}</span>
                </div>
                <div className="fila-monto"><Estado valor={estados[tramo]} /></div>
              </div>
            ))}
          </div>
          <p className="hint" aria-live="polite">
            {consultando
              ? "Consultando los servicios…"
              : caidos
                ? `${caidos === 1 ? "Un servicio no responde" : `${caidos} servicios no responden`}. Lo demás del panel puede fallar hasta que vuelva.`
                : "Los tres servicios responden con tu sesión."}
          </p>
          <button type="button" className="btn btn-ghost btn-sm" onClick={consultar} disabled={consultando}>
            Volver a consultar
          </button>
        </div>

        <div className="card aparece" style={{ "--i": 2 }}>
          <h2>Quién entra aquí</h2>
          <p style={{ marginTop: 0 }}>
            Solo el equipo de DataBridge. Una empresa usa su propio portal y ve solo su cartera, y sus
            clientes entran al portal de pago con su RUT y su código.
          </p>
          <p className="hint">
            Desde aquí no se ven los datos de ningún deudor.
          </p>
          <div className="acciones-fila">
            <Link className="btn btn-primary btn-sm" to="/admin/empresas">Ver las empresas</Link>
            <Link className="btn btn-ghost btn-sm" to="/admin/equipo">Equipo de DataBridge</Link>
          </div>
        </div>
      </div>
    </div>
  );
}

function Cifra({ dato, render }) {
  if (dato === "error") return <p role="status">Este servicio no responde. Las demás cifras siguen disponibles.</p>;
  if (!dato) return <p role="status">Consultando cifras…</p>;
  return render(dato);
}

function Estado({ valor }) {
  if (valor === "ok") return <span className="badge badge-ok">Responde</span>;
  if (valor === "caido") return <span className="badge badge-peligro">No responde</span>;
  return <span className="badge badge-muted">Consultando…</span>;
}

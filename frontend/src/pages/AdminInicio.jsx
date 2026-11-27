import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  SERVICIOS, avisosAEmpresas, estadoDe, estadoDelContacto, operacionDePagos, reclamosAbiertos, resumenDeDeudas,
  resumenDePagos,
} from "../api/admin";
import { useAuth } from "../store/authStore";
import {
  IconoAlerta,
  IconoCheck,
  IconoCartera,
  IconoEquipo,
  IconoEmpresa,
  IconoFlecha,
  IconoRecibo,
  IconoUsuario,
} from "../components/Iconos";
import { dinero, ESTADO_DEUDA } from "../utils/formato";
import { CarteraDona, PagosPorPasarela, RecuperadoDiario } from "../components/GraficosAdmin";

const numero = (n) => Number(n).toLocaleString("es-CL");
const sumar = (valores = {}) => Object.values(valores).reduce((total, valor) => total + Number(valor), 0);

/** Desde cuantos dias un reclamo sin respuesta se avisa en el inicio (#554). */
const DIAS_DE_RECLAMO = 7;

/**
 * Lo que necesita atencion hoy (#554), con lo que ya entregan Operacion, Reclamos y Campanas: primero la ley
 * apagada, que nunca puede pasar inadvertida, y despues lo que hay que arreglar. Cada uno, con su numero y a donde ir.
 */
function pendientesDe(revision) {
  const { contacto, avisos, pagos, reclamos } = revision;
  const lista = [];
  if (contacto && contacto !== "error") {
    if (!contacto.horarioLegal) {
      lista.push({ clave: "horario", grave: true, enlace: "/admin/campanas",
        titulo: "El horario de cobranza de la ley está apagado",
        detalle: "Se les puede escribir a los deudores a cualquier hora (CONTACTO_SIN_HORARIO). Es solo para probar: nunca con deudores reales." });
    }
    if (!contacto.limiteSemanal) {
      lista.push({ clave: "limite", grave: true, enlace: "/admin/campanas",
        titulo: "El límite de 2 correos por semana está apagado",
        detalle: "Se les puede escribir sin tope (CONTACTO_SIN_LIMITE). Es solo para probar: nunca con deudores reales." });
    }
  }
  const fallidos = avisos && avisos !== "error" ? Number(avisos.fallidos || 0) : 0;
  if (fallidos) {
    lista.push({ clave: "avisos", numero: fallidos, enlace: "/admin/operacion",
      titulo: fallidos === 1 ? "Un aviso a una empresa no llegó" : `${numero(fallidos)} avisos a las empresas no llegaron`,
      detalle: "Revisa en Operación a quién no le llegan y vuelve a ponerlos en cola." });
  }
  const p = pagos && pagos !== "error" ? pagos.pagos || {} : {};
  if (p.duplicados) {
    lista.push({ clave: "duplicados", numero: p.duplicados, enlace: "/admin/operacion",
      titulo: p.duplicados === 1 ? "Un pago cobrado dos veces por devolver" : `${numero(p.duplicados)} pagos cobrados dos veces por devolver`,
      detalle: "La empresa que cargó la deuda debe devolverle el dinero al deudor." });
  }
  if (p.abiertosHaceMasDeUnaHora) {
    lista.push({ clave: "abiertos", numero: p.abiertosHaceMasDeUnaHora, enlace: "/admin/operacion",
      titulo: p.abiertosHaceMasDeUnaHora === 1 ? "Un cobro abierto hace más de una hora" : `${numero(p.abiertosHaceMasDeUnaHora)} cobros abiertos hace más de una hora`,
      detalle: "Pregúntale a la pasarela cómo quedó, desde Operación." });
  }
  //  Vienen del mas antiguo al mas nuevo: si todos los de la pagina pasan el plazo y hay mas, son por lo menos esos.
  const filas = reclamos && reclamos !== "error" ? reclamos.filas : [];
  const viejos = filas.filter((r) => Number(r.dias) >= DIAS_DE_RECLAMO).length;
  const hayMas = viejos === filas.length && Number(reclamos?.pagina?.totalElements || 0) > filas.length;
  if (viejos) {
    lista.push({ clave: "reclamos", numero: viejos, enlace: "/admin/reclamos",
      titulo: viejos === 1 ? `Un reclamo sin respuesta hace ${DIAS_DE_RECLAMO} días o más`
        : `${numero(viejos)}${hayMas ? " o más" : ""} reclamos sin respuesta hace ${DIAS_DE_RECLAMO} días o más`,
      detalle: "Mientras tanto esas deudas no se cobran: la empresa tiene que responderlos." });
  }
  return lista;
}

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
  //  Lo que puede necesitar atencion (#554): sin valor mientras se consulta, "error" si no respondio.
  const [revision, setRevision] = useState({});

  function consultar() {
    setEstados({});
    setCifras({});
    setRevision({});
    const anotar = (clave) => (valor) => setRevision((r) => ({ ...r, [clave]: valor }));
    estadoDelContacto().then(anotar("contacto")).catch(() => anotar("contacto")("error"));
    avisosAEmpresas().then(anotar("avisos")).catch(() => anotar("avisos")("error"));
    operacionDePagos().then(anotar("pagos")).catch(() => anotar("pagos")("error"));
    reclamosAbiertos({ porPagina: 50 }).then(anotar("reclamos")).catch(() => anotar("reclamos")("error"));
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
  const nombre = user?.nombre?.trim().split(/\s+/)[0] || "Administrador";
  const revisando = ["contacto", "avisos", "pagos", "reclamos"].some((clave) => revision[clave] === undefined);
  const pendientes = revisando ? [] : pendientesDe(revision);
  const sinRevisar = [["contacto", "la ley"], ["avisos", "los avisos"], ["pagos", "los pagos"], ["reclamos", "los reclamos"]]
    .filter(([clave]) => revision[clave] === "error").map(([, que]) => que);
  const totalPendiente = pendientes.reduce((s, p) => s + (p.numero || 1), 0);

  return (
    <div className="admin-inicio">
      <header className="admin-inicio-cabecera aparece">
        <div className="admin-inicio-presentacion">
          <span className="eyebrow">Resumen general</span>
          <h1>Hola, {nombre}</h1>
          <p>Una vista simple del estado y la actividad de DataBridge.</p>
        </div>
        <div className="admin-inicio-estado">
          <span className={`admin-salud ${caidos ? "con-alerta" : ""}`} aria-live="polite">
            <span className="admin-salud-punto" />
            {consultando ? "Comprobando servicios" : caidos ? `${caidos} sin responder` : "Servicios operativos"}
          </span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={consultar} disabled={consultando}>
            Actualizar
          </button>
        </div>
      </header>

      <section className="admin-panel bandeja admin-pendientes aparece" aria-labelledby="pendientes-titulo"
               aria-busy={revisando}>
        <h2 id="pendientes-titulo">Lo que necesita atención</h2>
        {revisando ? (
          <p className="hint">Revisando…</p>
        ) : pendientes.length === 0 ? (
          sinRevisar.length ? null : <p className="bandeja-al-dia"><IconoCheck size={18} /> Nada espera tu atención.</p>
        ) : (
          <ul className="bandeja-lista">
            {pendientes.map((p) => (
              <li key={p.clave}>
                <Link to={p.enlace} className={`bandeja-item${p.grave ? " grave" : ""}`}>
                  <b className="bandeja-numero">{p.grave ? <IconoAlerta size={18} /> : numero(p.numero)}</b>
                  <span className="bandeja-texto"><b>{p.titulo}</b><span className="sub">{p.detalle}</span></span>
                  <IconoFlecha size={16} />
                </Link>
              </li>
            ))}
          </ul>
        )}
        {!revisando && sinRevisar.length ? <p className="hint">No se pudo revisar {sinRevisar.join(" ni ")}: inténtalo en un rato.</p> : null}
      </section>

      <section className="admin-resumen" aria-label="Cifras de la plataforma">
        <Resumen
          indice={1}
          titulo="Empresas activas"
          icono={<IconoEmpresa size={20} />}
          enlace="/admin/empresas"
          dato={cifras.debt}
          render={(r) => ({
            principal: numero(r.empresasActivas),
            detalle: `${numero(r.empresasSuspendidas)} suspendidas`,
          })}
        />
        <Resumen
          indice={2}
          titulo="Recuperado este mes"
          icono={<IconoCartera size={20} />}
          enlace="/admin/empresas"
          destacado
          dato={cifras.debt}
          render={(r) => ({
            principal: dinero(r.recuperado),
            detalle: `${dinero(r.comision)} de comisión`,
          })}
        />
        <Resumen
          indice={3}
          titulo="Pagos de hoy"
          icono={<IconoRecibo size={20} />}
          enlace="/admin/operacion"
          dato={cifras.payments}
          render={(r) => ({
            principal: numero(sumar(r.pagosHoy)),
            detalle: `${numero(sumar(r.pagosMes))} este mes`,
          })}
        />
        <Resumen
          indice={4}
          titulo="Requieren atención"
          icono={<IconoAlerta size={20} />}
          enlace="/admin/operacion"
          dato={revisando ? null : sinRevisar.length === 4 ? "error" : { total: totalPendiente }}
          render={(r) => ({
            principal: numero(r.total),
            detalle: r.total ? "El detalle, arriba" : "Nada pendiente",
          })}
        />
      </section>

      <section className="admin-inicio-graficos" aria-label="Recuperación y pagos">
        <div className="admin-panel aparece" style={{ "--i": 5 }}>
          <div className="admin-panel-cabecera">
            <div>
              <span className="eyebrow">Recuperación</span>
              <h2>Recuperado por día</h2>
            </div>
            <Link to="/admin/empresas" className="admin-enlace-discreto">
              Ver por empresa <IconoFlecha size={16} />
            </Link>
          </div>
          <Cifra dato={cifras.debt} render={(r) => <RecuperadoDiario puntos={r.recuperadoPorDia} />} />
        </div>

        <div className="admin-panel aparece" style={{ "--i": 6 }}>
          <div className="admin-panel-cabecera">
            <div>
              <span className="eyebrow">Pagos</span>
              <h2>Por pasarela</h2>
            </div>
            <Link to="/admin/operacion" className="admin-enlace-discreto">
              Ver operación <IconoFlecha size={16} />
            </Link>
          </div>
          <Cifra dato={cifras.payments} render={(r) => <PagosPorPasarela hoy={r.pagosHoy} mes={r.pagosMes} />} />
        </div>
      </section>

      <section className="admin-inicio-contenido">
        <div className="admin-panel admin-panel-cartera aparece" style={{ "--i": 7 }}>
          <div className="admin-panel-cabecera">
            <div>
              <span className="eyebrow">Cartera</span>
              <h2>Deudas por estado</h2>
            </div>
            <Link to="/admin/campanas" className="admin-enlace-discreto">
              Ver campañas <IconoFlecha size={16} />
            </Link>
          </div>
          <Cifra dato={cifras.debt} render={(r) => (
            <CarteraPorEstado deudas={r.deudasPorEstado} />
          )} />
        </div>

        <div className="admin-panel admin-panel-servicios aparece" style={{ "--i": 8 }}>
          <div className="admin-panel-cabecera">
            <div>
              <span className="eyebrow">Sistema</span>
              <h2>Estado de servicios</h2>
            </div>
          </div>
          <EstadoServicios estados={estados} />
        </div>

        <div className="admin-panel admin-panel-atajos aparece" style={{ "--i": 9 }}>
          <div className="admin-panel-cabecera">
            <div>
              <span className="eyebrow">Accesos rápidos</span>
              <h2>Gestionar</h2>
            </div>
          </div>
          <div className="admin-atajos-mosaico">
            <Link to="/admin/empresas" className="admin-atajo">
              <span className="admin-atajo-icono"><IconoEmpresa size={19} /></span>
              <span><b>Empresas</b><small>Gestionar</small></span>
            </Link>
            <Link to="/admin/deudores" className="admin-atajo">
              <span className="admin-atajo-icono"><IconoUsuario size={19} /></span>
              <span><b>Deudores</b><small>Buscar</small></span>
            </Link>
            <Link to="/admin/equipo" className="admin-atajo">
              <span className="admin-atajo-icono"><IconoEquipo size={19} /></span>
              <span><b>Equipo</b><small>Administrar</small></span>
            </Link>
          </div>
          <p className="admin-nota">
            Los datos de un deudor se ven solo en su ficha, y abrirla queda en la bitácora.
          </p>
        </div>
      </section>
    </div>
  );
}

function EstadoServicios({ estados }) {
  return (
    <div className="admin-servicios">
      {SERVICIOS.map(({ tramo, nombre, servicio }) => {
        const estado = estados[tramo] || "consultando";
        const textoEstado = estado === "ok" ? "Operativo" : estado === "caido" ? "Sin respuesta" : "Verificando";
        return (
          <div className="admin-servicio" key={tramo}>
            <div className="admin-servicio-cabecera">
              <span
                className={`admin-servicio-punto ${estado}`}
                role="img"
                aria-label={`${nombre}: ${textoEstado}`}
              />
              <small>{servicio}</small>
            </div>
            <b>{nombre}</b>
          </div>
        );
      })}
    </div>
  );
}

function CarteraPorEstado({ deudas = {} }) {
  const estados = Object.entries(ESTADO_DEUDA).map(([estado, etiqueta]) => ({
    estado,
    etiqueta,
    cantidad: Number(deudas[estado] ?? 0),
  }));
  const total = estados.reduce((acumulado, item) => acumulado + item.cantidad, 0);

  return (
    <div className="admin-cartera-detalle">
      <div className="admin-cartera-total">
        <CarteraDona deudas={deudas} />
        <div>
          <span>Total de la cartera</span>
          <b>{numero(total)}</b>
        </div>
        <p>Deudas registradas, agrupadas por su estado actual.</p>
      </div>
      <div className="admin-estados-deuda">
        {estados.map(({ estado, etiqueta, cantidad }) => {
          const porcentaje = total ? Math.round((cantidad / total) * 100) : 0;
          return (
            <Link
              key={estado}
              to="/admin/campanas"
              className={`admin-estado-deuda estado-${estado}`}
              style={{ "--progreso": `${porcentaje}%` }}
            >
              <span className="admin-estado-deuda-cabecera">
                <span className="admin-estado-deuda-nombre">
                  <i aria-hidden="true" />
                  {etiqueta}
                </span>
                <small>{porcentaje}%</small>
              </span>
              <b>{numero(cantidad)}</b>
              <span className="admin-estado-deuda-barra" aria-hidden="true"><i /></span>
            </Link>
          );
        })}
      </div>
    </div>
  );
}

function Resumen({ indice, titulo, icono, enlace, dato, render, destacado = false }) {
  return (
    <Link
      to={enlace}
      className={`admin-resumen-tarjeta aparece${destacado ? " destacado" : ""}`}
      style={{ "--i": indice }}
    >
      <div className="admin-resumen-titulo">
        <span className="admin-resumen-icono">{icono}</span>
        <span>{titulo}</span>
      </div>
      <Cifra dato={dato} render={(valor) => {
        const contenido = render(valor);
        return (
          <div className="admin-resumen-valor">
            <b>{contenido.principal}</b>
            <span>{contenido.detalle}</span>
          </div>
        );
      }} />
    </Link>
  );
}

function Cifra({ dato, render }) {
  if (dato === "error") return <p className="admin-dato-estado error-dato" role="status">No disponible</p>;
  if (!dato) return <p className="admin-dato-estado" role="status">Consultando…</p>;
  return render(dato);
}

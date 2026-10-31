import { useCallback, useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import {
  crearPersona, devolverAcceso, historialDeEmpresa, listarPersonal, obtenerEmpresa, quitarAcceso,
  reactivarEmpresa, suspenderEmpresa,
} from "../api/admin";
import { ESTADO_DEL_MANDATO, TIPO_DE_EMPRESA, dinero, fecha, rutLegible } from "../utils/formato";
import { useAuth } from "../store/authStore";
import Cargando from "../components/Cargando";
import { FormPersona, Historial, TablaPersonas } from "../components/AdminPersonas";
import { IconoVolver } from "../components/Iconos";

/**
 * La ficha de una empresa: quien es, cuanto hay en su cartera (en totales),
 * con quien tiene mandato y quienes entran en su nombre. Desde aqui se le da
 * acceso a una persona, o se le quita.
 */
export default function AdminEmpresa() {
  const { rut } = useParams();
  const { user } = useAuth();
  const llegada = useLocation().state;
  const [empresa, setEmpresa] = useState(null);
  const [personas, setPersonas] = useState([]);
  const [acciones, setAcciones] = useState([]);
  const [aviso, setAviso] = useState(llegada?.aviso || "");
  const [error, setError] = useState(llegada?.error || "");
  const [noExiste, setNoExiste] = useState(false);

  //  La empresa viene de ms-debt; sus personas y su historia, de ms-auth. Si
  //  una parte falla, la otra se muestra igual.
  const cargarPersonas = useCallback(async () => {
    const [gente, historia] = await Promise.all([listarPersonal(rut), historialDeEmpresa(rut)]);
    setPersonas(gente);
    setAcciones(historia);
  }, [rut]);

  useEffect(() => {
    obtenerEmpresa(rut).then(setEmpresa).catch((err) => {
      if (err.status === 404) setNoExiste(true);
      else setError(err.message);
    });
    cargarPersonas().catch((err) => setError(err.message));
  }, [rut, cargarPersonas]);

  async function agregar(persona) {
    setAviso("");
    setError("");
    await crearPersona(rut, persona);
    setAviso(`A ${persona.correo} le llegó su invitación para entrar al portal de empresas.`);
    await cargarPersonas();
  }

  async function cambiarAcceso(persona, accion) {
    if (accion === "quitar"
        && !window.confirm(`¿Quitarle el acceso a ${persona.nombre}? Sus sesiones se cierran y no le llegan más enlaces.`)) {
      return;
    }
    setAviso("");
    setError("");
    try {
      await (accion === "quitar" ? quitarAcceso(persona.id) : devolverAcceso(persona.id));
      await cargarPersonas();
    } catch (err) {
      setError(err.message);
    }
  }

  async function cambiarEstadoEmpresa(accion) {
    if (accion === "suspender") {
      if (!window.confirm(`¿Suspender a ${empresa.nombre}? Sus personas no podrán entrar al portal y sus claves de API `
        + "dejan de servir. A sus deudores no se les escribe y no pueden pagar ni repactar hasta que la reactives. "
        + "No se borra nada.")) {
        return;
      }
    } else {
      if (!window.confirm(`¿Reactivar a ${empresa.nombre}? Sus personas y sus claves de API vuelven a funcionar, y a `
        + "sus deudores se les vuelve a escribir y pueden pagar, desde donde quedó.")) {
        return;
      }
    }
    setAviso("");
    setError("");
    try {
      const resp = await (accion === "suspender" ? suspenderEmpresa(rut) : reactivarEmpresa(rut));
      setEmpresa(resp);
      setAviso(accion === "suspender" ? `${empresa.nombre} quedó suspendida.` : `${empresa.nombre} quedó reactivada.`);
      await cargarPersonas();
    } catch (err) {
      setError(err.message);
    }
  }

  if (noExiste) {
    return (
      <div>
        <Link className="btn btn-ghost btn-sm" to="/admin/empresas" style={{ marginBottom: 14 }}><IconoVolver size={16} /> Empresas</Link>
        <div className="empty bloque">No hay ninguna empresa con el RUT {rutLegible(rut)}.</div>
      </div>
    );
  }
  if (empresa === null && !error) return <Cargando tarjetas={3} />;

  const conAcceso = personas.filter((p) => p.habilitada).length;

  return (
    <div>
      <Link className="btn btn-ghost btn-sm" to="/admin/empresas" style={{ marginBottom: 14 }}><IconoVolver size={16} /> Empresas</Link>
      <header className="topbar aparece">
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <h1>{empresa?.nombre || rutLegible(rut)}</h1>
            {empresa ? (
              empresa.activa ? (
                <span className="badge badge-ok">Activa</span>
              ) : (
                <span className="badge badge-muted">Suspendida</span>
              )
            ) : null}
          </div>
          {empresa ? (
            <p>
              {empresa.razonSocial} · RUT {rutLegible(empresa.rut)} · {TIPO_DE_EMPRESA[empresa.tipo] || empresa.tipo}
              {" "}· en DataBridge desde el {fecha(empresa.creadaEl)}
            </p>
          ) : null}
        </div>
        {empresa ? (
          <div>
            {empresa.activa ? (
              <button className="btn btn-danger btn-sm" onClick={() => cambiarEstadoEmpresa("suspender")}>
                Suspender empresa
              </button>
            ) : (
              <button className="btn btn-outline btn-sm" onClick={() => cambiarEstadoEmpresa("reactivar")}>
                Reactivar empresa
              </button>
            )}
          </div>
        ) : null}
      </header>
      {error ? <div className="error" role="alert">{error}</div> : null}
      {aviso ? <div className="notice" role="status">{aviso}</div> : null}

      {empresa ? (
        <section className="grid-3 stats bloque">
          <div className="card stat aparece" style={{ "--i": 1 }}>
            <span>Deudas en gestión</span>
            <b>{empresa.enGestion}</b>
            <small>de {empresa.deudas} que ha tenido en cartera</small>
          </div>
          <div className="card stat aparece" style={{ "--i": 2 }}>
            <span>Por cobrar</span>
            <b className="totales">
              {empresa.porCobrar.length === 0 ? "–" : empresa.porCobrar.map((m) => (
                <span key={m.moneda}>{dinero(m.monto, m.moneda)}</span>
              ))}
            </b>
            <small>Totales: aquí no se ve ninguna deuda ni ningún deudor</small>
          </div>
          <div className="card stat aparece" style={{ "--i": 3 }}>
            <span>Personas con acceso</span>
            <b>{conAcceso}</b>
            <small>de {personas.length} con cuenta</small>
          </div>
        </section>
      ) : null}

      <div className="grid-2">
        <div>
          <div className="card bloque aparece" style={{ "--i": 4 }}>
            <h3>Quiénes entran por esta empresa</h3>
            <TablaPersonas personas={personas} tuCorreo={user?.correo} alCambiar={cambiarAcceso}
                           vacio="Nadie tiene acceso todavía. Dale acceso a su primera persona." />
          </div>

          {empresa ? (
            <div className="card bloque aparece" style={{ "--i": 5 }}>
              <h3>Mandatos</h3>
              {empresa.mandatos.length === 0 ? (
                <div className="empty">
                  Sin mandatos. {empresa.tipo === "agencia"
                    ? "La agencia los declara en su portal, en Campañas."
                    : "Cobra lo suyo, sin una agencia de por medio."}
                </div>
              ) : (
                <div className="lista">
                  {empresa.mandatos.map((m) => (
                    <div className="fila" key={`${m.papel}-${m.rut}-${m.desde}`}>
                      <div className="fila-que">
                        <b>{m.papel === "cobra_para" ? `Cobra por cuenta de ${m.nombre}` : `${m.nombre} cobra por ella`}</b>
                        <span>
                          RUT {rutLegible(m.rut)} · desde el {fecha(m.desde)}{m.hasta ? ` hasta el ${fecha(m.hasta)}` : ""}
                        </span>
                      </div>
                      <div className="fila-monto">
                        {(() => {
                          const [texto, clase] = ESTADO_DEL_MANDATO[m.estado] || ["Terminado", "badge-muted"];
                          return <span className={`badge ${clase}`}>{texto}</span>;
                        })()}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ) : null}

          <div className="card aparece" style={{ "--i": 6 }}>
            <h3>Lo que se hizo desde el panel</h3>
            <Historial acciones={acciones} />
          </div>
        </div>

        <div className="card aparece" style={{ "--i": 4 }}>
          <h3>Darle acceso a una persona</h3>
          {empresa?.activa === false ? (
            <p className="hint" style={{ marginTop: 10 }}>
              Mientras esté suspendida no se le pueden agregar personas.
            </p>
          ) : (
            <>
              <FormPersona prefijo="p" boton="Dar acceso" alGuardar={agregar} />
              <p className="hint">
                Queda como usuario de esta empresa: ve solo su cartera y no entra a este panel. Le llega un enlace a su
                correo, y entra sin contraseña.
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

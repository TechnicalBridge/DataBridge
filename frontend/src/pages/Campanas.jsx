import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { cambiarEstadoCampana, listarCampanas } from "../api/campanas";
import { opcionesDeCarga } from "../api/deudas";
import { fecha } from "../utils/formato";
import Cargando from "../components/Cargando";
import FormCampana, { TRAMOS } from "../components/FormCampana";

const ESTADO = {
  en_curso: ["En curso", "badge-ok"],
  pausada: ["Pausada", "badge-warn"],
  terminada: ["Terminada", "badge-muted"],
};

/**
 * Las campañas de la empresa: las crea, las cambia, las pausa y las termina.
 * Entran por el mismo camino que las del contrato, con sus mismas reglas. Los
 * deudores se le cargan a cada una desde Cargar cartera.
 */
export default function Campanas() {
  const [campanas, setCampanas] = useState(null);
  const [acreedores, setAcreedores] = useState([]);
  const [editando, setEditando] = useState(null);
  //  Cambia cada vez que el formulario tiene que partir de cero.
  const [vuelta, setVuelta] = useState(0);
  const [cambiando, setCambiando] = useState("");
  const [error, setError] = useState("");
  const [hecho, setHecho] = useState("");

  const cargar = () => listarCampanas().then(setCampanas);

  useEffect(() => {
    cargar().catch((err) => {
      setError(err.message);
      setCampanas([]);
    });
    opcionesDeCarga()
      .then((o) => setAcreedores(o.acreedores || []))
      .catch((err) => setError(err.message));
  }, []);

  function nueva() {
    setEditando(null);
    setVuelta((v) => v + 1);
  }

  function editar(c) {
    setEditando(c);
    setVuelta((v) => v + 1);
    setError("");
    setHecho("");
    document.getElementById("form-campana")?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  async function guardada(campana) {
    setError("");
    setHecho(editando
      ? `Se guardaron los cambios de "${campana.nombre}".`
      : `Se creó "${campana.nombre}" (${campana.idExterno}). Ya le puedes cargar deudores.`);
    nueva();
    await cargar();
  }

  async function cambiar(c, estado, pregunta) {
    if (pregunta && !window.confirm(pregunta)) return;
    setCambiando(c.idExterno);
    setError("");
    setHecho("");
    try {
      await cambiarEstadoCampana(c.idExterno, estado);
      if (editando?.idExterno === c.idExterno) nueva();
      await cargar();
    } catch (err) {
      setError(err.message);
    } finally {
      setCambiando("");
    }
  }

  if (campanas === null) return <Cargando tarjetas={2} />;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Campañas</h1>
          <p>Tú decides cuándo y cuántas veces se contacta al deudor; DataBridge le escribe dentro de lo que permite la ley.</p>
        </div>
      </header>
      {error ? <div className="error">{error}</div> : null}
      {hecho ? <div className="notice">{hecho}</div> : null}

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <h3>Tus campañas</h3>
          {campanas.length === 0 ? (
            <div className="empty">Todavía no hay campañas. Crea una y cárgale sus deudores.</div>
          ) : (
            <div className="campanas">
              {campanas.map((c) => {
                const [estado, clase] = ESTADO[c.estado] || [c.estado, "badge-muted"];
                const ocupada = cambiando === c.idExterno;
                return (
                  <div key={c.idExterno} className={`campana${editando?.idExterno === c.idExterno ? " elegida" : ""}`}>
                    <div className="campana-cabeza">
                      <div className="fila-que">
                        <b>{c.nombre}</b>
                        <span>{c.propia ? "Cartera propia" : c.acreedor} · <span className="mono">{c.idExterno}</span></span>
                      </div>
                      <span className={`badge ${clase}`}>{estado}</span>
                    </div>
                    <div className="totales campana-cifras">
                      <span>{fecha(c.inicio)} – {c.fin ? fecha(c.fin) : "sin fin"}</span>
                      <span>{c.intentos} {c.intentos === 1 ? "contacto" : "contactos"}, días {(c.cadenciaDias || []).join(", ")}</span>
                      {c.descuentoPorTramo ? (
                        <span>
                          Descuento: {TRAMOS.map(([t]) => `${c.descuentoPorTramo[t] ?? 0}%`).join(" · ")}
                        </span>
                      ) : null}
                    </div>
                    <div className="totales campana-cifras">
                      <span><b className="num">{c.deudas}</b> {c.deudas === 1 ? "deuda" : "deudas"}</span>
                      <span><b className="num">{c.contactos}</b> {c.contactos === 1 ? "contacto enviado" : "contactos enviados"}</span>
                      <span><b className="num">{c.saldadas}</b> {c.saldadas === 1 ? "pagada" : "pagadas"}</span>
                    </div>
                    {c.estado !== "terminada" ? (
                      <div className="fila-acciones">
                        <Link className="btn btn-primary btn-sm"
                              to={`/databridge/cargar?campana=${encodeURIComponent(c.idExterno)}`}>
                          Cargar deudores
                        </Link>
                        <button type="button" className="btn btn-ghost btn-sm" disabled={ocupada} onClick={() => editar(c)}>
                          Cambiar
                        </button>
                        {c._links?.pausar ? (
                          <button type="button" className="btn btn-soft btn-sm" disabled={ocupada}
                                  onClick={() => cambiar(c, "pausada")}>Pausar</button>
                        ) : null}
                        {c._links?.reanudar ? (
                          <button type="button" className="btn btn-soft btn-sm" disabled={ocupada}
                                  onClick={() => cambiar(c, "en_curso")}>Reanudar</button>
                        ) : null}
                        {c._links?.terminar ? (
                          <button type="button" className="btn btn-peligro btn-sm" disabled={ocupada}
                                  onClick={() => cambiar(c, "terminada",
                                    `¿Terminar "${c.nombre}"? No se contacta a nadie más por ella y no se puede reanudar.`)}>
                            Terminar
                          </button>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <div className="card aparece" id="form-campana" style={{ "--i": 2 }}>
          <h3>{editando ? `Cambiar "${editando.nombre}"` : "Nueva campaña"}</h3>
          <FormCampana key={vuelta} acreedores={acreedores} editando={editando} onGuardada={guardada}
                       onCancelar={editando ? nueva : null} />
          <p className="hint">
            Los deudores entran con <b>Cargar deudores</b>, en cada campaña, o desde{" "}
            <Link className="link-btn" to="/databridge/cargar">Cargar cartera</Link>. Se deja de contactar a quien
            paga, repacta, reclama o sale de la cobranza.
          </p>
        </div>
      </div>
    </div>
  );
}

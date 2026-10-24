import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { obtenerDeuda, repactar, simularPlan } from "../api/deudas";
import { dinero, fecha, porcentaje } from "../utils/formato";
import BarraEstado from "../components/BarraEstado";
import Cargando from "../components/Cargando";
import { IconoFlecha, IconoVolver } from "../components/Iconos";

const MIN = 3;
const MAX = 24;
const RAPIDOS = [3, 6, 12, 18, 24];

/**
 * Cuanto espera el control del plazo quieto antes de simular. Antes cada paso
 * del deslizador era una consulta, y deslizarlo de punta a punta mandaba
 * veinte seguidas: el gateway las cortaba (120 por minuto) y la pagina se
 * quedaba en "Demasiadas solicitudes". Ahora se consulta el plazo en que se
 * detiene, y cada plazo ya calculado se guarda: volver a el no consulta nada.
 */
const ESPERA_MS = 1000;

/**
 * Simular un plan de cuotas y aceptarlo.
 *
 * Simular no compromete nada: el deudor mueve el plazo y ve la cuota. Recien
 * al confirmar se crea el plan, y la empresa se entera por un evento.
 */
export default function Repact() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [deuda, setDeuda] = useState(null);
  const [meses, setMeses] = useState(6);
  const [pedido, setPedido] = useState(6);
  const [planes, setPlanes] = useState({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const enCurso = useRef(new Set());

  useEffect(() => {
    obtenerDeuda(id).then(setDeuda).catch((err) => setError(err.message));
  }, [id]);

  // El plazo se simula cuando el control lleva un momento quieto.
  useEffect(() => {
    if (planes[meses]) return undefined;
    const t = setTimeout(() => setPedido(meses), ESPERA_MS);
    return () => clearTimeout(t);
  }, [meses, planes]);

  useEffect(() => {
    if (planes[pedido] || enCurso.current.has(pedido)) return;
    enCurso.current.add(pedido);
    simularPlan(id, pedido)
      .then((nuevo) => {
        setPlanes((antes) => ({ ...antes, [pedido]: nuevo }));
        setError("");
      })
      .catch((err) => setError(err.message))
      .finally(() => enCurso.current.delete(pedido));
  }, [id, pedido, planes]);

  async function confirmar() {
    setBusy(true);
    setError("");
    try {
      await repactar(id, meses);
      navigate(`/app/pagar/${id}`);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  if (!deuda) return error ? <div className="error">{error}</div> : <Cargando tarjetas={1} />;

  const moneda = deuda.moneda;
  const plan = planes[meses];
  const esperando = !plan && pedido !== meses;
  const pct = `${((meses - MIN) / (MAX - MIN)) * 100}%`;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <span className="eyebrow">{deuda.acreedor}</span>
          <h1>Pagar en cuotas</h1>
          <p>{deuda.concepto}, saldo {dinero(deuda.totalHoy ?? deuda.saldo, moneda)}</p>
        </div>
        <Link className="btn btn-ghost btn-sm" to="/app">
          <IconoVolver size={16} />
          Volver
        </Link>
      </header>
      {error ? <div className="error">{error}</div> : null}

      <div className="card bloque aparece" style={{ "--i": 1 }}>
        <BarraEstado deuda={deuda} />
      </div>

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 2 }}>
          <div className="plazo-cab">
            <div>
              <span className="eyebrow">Plazo</span>
              <b key={meses}>{meses}<small>meses</small></b>
            </div>
            <div style={{ display: "flex", gap: "6px", alignItems: "center", flexWrap: "wrap", justifyContent: "flex-end" }}>
              <span className="badge badge-muted">CAE {porcentaje(plan?.cae, 2)}</span>
              {deuda.tasaInteresMensual ? (
                <span className="badge badge-muted">{porcentaje(deuda.tasaInteresMensual)} mensual</span>
              ) : (
                <span className="badge badge-ok">Sin interés</span>
              )}
            </div>
          </div>

          <div className="plazo">
            <input type="range" min={MIN} max={MAX} step={1} value={meses} aria-label="Plazo en meses"
                   style={{ "--pct": pct }} onChange={(e) => setMeses(Number(e.target.value))} />
            <div className="plazo-extremos"><span>{MIN} meses</span><span>{MAX} meses</span></div>
          </div>

          <div className="atajos">
            {RAPIDOS.map((m) => (
              <button key={m} type="button" className={`chip${m === meses ? " on" : ""}`} onClick={() => setMeses(m)}>
                {m} meses
              </button>
            ))}
          </div>

          {esperando ? (
            <div className="espera" key={meses} style={{ "--espera": `${ESPERA_MS}ms` }}><span /></div>
          ) : (
            <div className="espera" style={{ visibility: "hidden" }} />
          )}
          <div className="espera-texto">
            {plan ? null : esperando ? "Leyendo el plazo…" : <><span className="girando" /> Calculando la cuota…</>}
          </div>

          <div className={`cifras${plan ? "" : " calculando"}`}>
            <div className="cifra principal">
              <span>Cuota mensual</span>
              <b key={`c${meses}${!!plan}`}>{plan ? dinero(plan.monthlyAmount, moneda) : "—"}</b>
            </div>
            <div className="cifra">
              <span>Última cuota</span>
              <b key={`u${meses}${!!plan}`}>{plan ? dinero(plan.lastAmount, moneda) : "—"}</b>
            </div>
            <div className="cifra">
              <span>Costo total</span>
              <b key={`t${meses}${!!plan}`}>{plan ? dinero(plan.costoTotal ?? plan.total, moneda) : "—"}</b>
            </div>
          </div>
          {deuda.tasaInteresMensual && !plan ? (
            <p className="hint">
              Las cuotas llevan el {porcentaje(deuda.tasaInteresMensual)} de interés mensual que pactaste con{" "}
              {deuda.acreedor}. Al calcular la cuota ves el costo total y la CAE (el costo del convenio en un año, con
              todo incluido).
            </p>
          ) : deuda.tasaInteresMensual ? (
            <p className="hint">
              Se repacta lo que debes hoy: {dinero(plan.capital, moneda)}
              {Number(plan.interesMora) > 0 ? ` más ${dinero(plan.interesMora, moneda)} de intereses por mora` : ""},{" "}
              {dinero(plan.aRepactar, moneda)}. Las cuotas llevan el {porcentaje(plan.tasaInteresMensual)} de interés
              mensual que pactaste con {deuda.acreedor}: en total pagas {dinero(plan.costoTotal ?? plan.total, moneda)}, y{" "}
              {dinero(plan.interesConvenio, moneda)} son intereses del convenio. CAE {porcentaje(plan.cae, 2)} (el costo
              del convenio en un año, con todo incluido). La última cuota absorbe el redondeo.
              {moneda === "UF" ? " En UF, cada cuota se paga al valor de la UF del día en que pagas." : ""}
            </p>
          ) : (
            <p className="hint">
              El costo total es lo que debes hoy: {plan ? dinero(plan.costoTotal ?? plan.total, moneda) : "—"}, no se
              cobran intereses. CAE {porcentaje(plan?.cae, 2)} (el costo del convenio en un año, con todo incluido). La
              última cuota absorbe el redondeo.
              {moneda === "UF" ? " En UF, cada cuota se paga al valor de la UF del día en que pagas." : ""}
            </p>
          )}
          <button className="btn btn-primary btn-block" style={{ marginTop: 18 }} disabled={busy || !plan}
                  onClick={confirmar}>
            {busy ? <><span className="girando" /> Aceptando…</> : <>Aceptar {meses} cuotas <IconoFlecha size={16} /></>}
          </button>
        </div>

        <div className="card aparece" style={{ "--i": 3 }}>
          <h3>Calendario</h3>
          {plan ? (
            <ol className="calendario" key={meses}>
              {plan.cuotas.map((c, i) => (
                <li key={c.number} style={{ "--i": Math.min(i, 14) }}>
                  <span className="num">{c.number}</span>
                  <span>{fecha(c.dueDate)}</span>
                  <span className="monto">
                    {dinero(c.amount, moneda)}
                    {Number(c.interest) > 0 ? <small>{dinero(c.interest, moneda)} de interés</small> : null}
                  </span>
                </li>
              ))}
            </ol>
          ) : (
            <div style={{ display: "grid", gap: 6 }}>
              {Array.from({ length: Math.min(meses, 8) }, (_, i) => (
                <div key={i} className="esqueleto" style={{ height: 48 }} />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

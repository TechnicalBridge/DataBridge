import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { crearEmpresa, crearPersona, listarEmpresas } from "../api/admin";
import { TIPO_DE_EMPRESA, dinero, rutLegible } from "../utils/formato";
import Cargando from "../components/Cargando";
import ComisionesDelMes from "../components/ComisionesDelMes";

const VACIA = { rut: "", razonSocial: "", nombre: "", tipo: "agencia", personaNombre: "", personaCorreo: "" };

/**
 * Las empresas que usan DataBridge, y el alta de una nueva con su primera
 * persona. Del lado de cada empresa se ven totales: cuantas deudas y cuanto
 * falta por cobrar. Lo que hay adentro de su cartera lo ve solo ella.
 */
export default function AdminEmpresas() {
  const navegar = useNavigate();
  const [empresas, setEmpresas] = useState(null);
  const [form, setForm] = useState(VACIA);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [errorDelAlta, setErrorDelAlta] = useState("");

  useEffect(() => {
    listarEmpresas().then(setEmpresas).catch((err) => {
      setError(err.message);
      setEmpresas([]);
    });
  }, []);

  const campo = (nombre) => (e) => setForm((f) => ({ ...f, [nombre]: e.target.value }));
  const listo = form.rut.trim() && form.razonSocial.trim() && form.personaNombre.trim() && form.personaCorreo.trim();

  /**
   * La empresa vive en ms-debt y su gente en ms-auth: son dos pasos. Si el
   * segundo falla, la empresa ya quedo registrada y se sigue en su ficha, donde
   * se le puede agregar la persona.
   */
  async function darDeAlta(e) {
    e.preventDefault();
    setBusy(true);
    setErrorDelAlta("");
    let creada;
    try {
      creada = await crearEmpresa({
        rut: form.rut.trim(),
        razonSocial: form.razonSocial.trim(),
        nombre: form.nombre.trim() || null,
        tipo: form.tipo,
      });
    } catch (err) {
      setErrorDelAlta(err.message);
      setBusy(false);
      return;
    }
    try {
      await crearPersona(creada.rut, { nombre: form.personaNombre.trim(), correo: form.personaCorreo.trim() });
      navegar(`/admin/empresas/${creada.rut}`, {
        state: { aviso: `${creada.nombre} quedó registrada, y a ${form.personaCorreo.trim()} le llegó su invitación.` },
      });
    } catch (err) {
      navegar(`/admin/empresas/${creada.rut}`, {
        state: { error: `${creada.nombre} quedó registrada, pero no se pudo dar de alta a su primera persona: ${err.message} Agrégala aquí.` },
      });
    }
  }

  if (empresas === null) return <Cargando tarjetas={2} />;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Empresas</h1>
          <p>Las agencias de cobranza y los acreedores que usan DataBridge. De cada una se ven sus totales, nunca su cartera.</p>
        </div>
      </header>
      {error ? <div className="error" role="alert">{error}</div> : null}

      <ComisionesDelMes />

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <h3>Empresas registradas</h3>
          {empresas.length === 0 ? (
            <div className="empty">Todavía no hay empresas. Da de alta la primera.</div>
          ) : (
            <div className="tabla-scroll">
              <table className="table">
                <thead>
                  <tr><th>Empresa</th><th>Tipo</th><th className="num">Deudas en gestión</th><th className="num">Por cobrar</th><th /></tr>
                </thead>
                <tbody>
                  {empresas.map((e) => (
                    <tr key={e.rut}>
                      <td>
                        <Link to={`/admin/empresas/${e.rut}`}>{e.nombre}</Link>
                        {!e.activa ? <span className="badge badge-muted" style={{ marginLeft: 6 }}>Suspendida</span> : null}
                        <span className="sub sin-corte">{rutLegible(e.rut)}</span>
                      </td>
                      <td>{TIPO_DE_EMPRESA[e.tipo] || e.tipo}</td>
                      <td className="num">
                        {e.enGestion}
                        <span className="sub">de {e.deudas}</span>
                      </td>
                      <td className="num">
                        {e.porCobrar.length === 0 ? "–" : e.porCobrar.map((m) => (
                          <span key={m.moneda} style={{ display: "block" }}>{dinero(m.monto, m.moneda)}</span>
                        ))}
                      </td>
                      <td><Link className="btn btn-ghost btn-sm" to={`/admin/empresas/${e.rut}`}>Ver</Link></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="card aparece" style={{ "--i": 2 }}>
          <h3>Dar de alta una empresa</h3>
          <form onSubmit={darDeAlta}>
            {errorDelAlta ? <div className="error" role="alert">{errorDelAlta}</div> : null}
            <div className="field">
              <label htmlFor="e-rut">RUT de la empresa</label>
              <input id="e-rut" value={form.rut} maxLength={20} autoComplete="off" inputMode="text"
                     onChange={campo("rut")} placeholder="76.086.428-5" required />
            </div>
            <div className="field">
              <label htmlFor="e-razon">Razón social</label>
              <input id="e-razon" value={form.razonSocial} maxLength={160} autoComplete="off"
                     onChange={campo("razonSocial")} placeholder="Empresa Test SpA" required />
            </div>
            <div className="campos-2">
              <div className="field">
                <label htmlFor="e-nombre">Nombre de fantasía</label>
                <input id="e-nombre" value={form.nombre} maxLength={120} autoComplete="off"
                       onChange={campo("nombre")} placeholder="Empresa Test" />
              </div>
              <div className="field">
                <label htmlFor="e-tipo">Qué es</label>
                <select id="e-tipo" value={form.tipo} onChange={campo("tipo")}>
                  <option value="agencia">Agencia de cobranza</option>
                  <option value="acreedor">Acreedor que cobra directo</option>
                </select>
              </div>
            </div>

            <div className="lista-grupo">Su primera persona</div>
            <div className="field">
              <label htmlFor="e-persona">Nombre</label>
              <input id="e-persona" value={form.personaNombre} maxLength={160} autoComplete="off"
                     onChange={campo("personaNombre")} placeholder="Ana Soto" required />
            </div>
            <div className="field">
              <label htmlFor="e-correo">Correo</label>
              <input id="e-correo" type="email" value={form.personaCorreo} maxLength={254} autoComplete="off"
                     onChange={campo("personaCorreo")} placeholder="ana.soto@empresatest.cl" required />
            </div>
            <button className="btn btn-primary btn-block" disabled={busy || !listo}>
              {busy ? <><span className="girando" /> Dando de alta…</> : "Dar de alta"}
            </button>
          </form>
          <p className="hint">
            A esa persona le llega un enlace a su correo para entrar al portal de empresas. Los acreedores de una
            agencia no se dan de alta aquí: los presenta ella con el mandato.
          </p>
        </div>
      </div>
    </div>
  );
}

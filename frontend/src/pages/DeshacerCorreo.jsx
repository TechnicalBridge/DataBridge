import { useState } from "react";
import { Link } from "react-router-dom";
import Logo from "../components/Logo";
import TemaToggle from "../components/TemaToggle";
import { IconoVolver } from "../components/Iconos";
import { rutLegible } from "../utils/formato";
import { deshacerCambioDeCorreo } from "../api/deudas";

/**
 * Deshacer un cambio de correo que el deudor no pidio (#471). Va sin sesion, porque quien la perdio no puede
 * entrar: el codigo que llego al correo anterior prueba que es el dueno. Al usarlo, el acceso vuelve a ese
 * correo y se cierran todas las sesiones.
 */
export default function DeshacerCorreo() {
  const [rut, setRut] = useState("");
  const [codigo, setCodigo] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [listo, setListo] = useState(false);

  async function deshacer(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await deshacerCambioDeCorreo(rut, codigo);
      setListo(true);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-shell">
      <TemaToggle flotante />
      <section className="auth-hero">
        <div className="brand-row aparece">
          <Logo size={46} />
          <div>
            <div className="brand-name">DataBridge</div>
            <div className="brand-sub">Portal de pago</div>
          </div>
        </div>
        <div className="hero-copy">
          <h1 className="aparece" style={{ "--i": 1 }}>¿Te cambiaron el correo sin pedirlo?</h1>
          <p className="aparece" style={{ "--i": 2 }}>
            Cuando alguien cambia el correo de tu acceso, a tu correo anterior le llega un aviso con un código para
            deshacerlo. Vale 7 días.
          </p>
        </div>
        <Link to="/login" className="btn btn-ghost btn-sm volver-link aparece" style={{ "--i": 3 }}>
          <IconoVolver size={16} /> Volver a la entrada
        </Link>
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          {listo ? (
            <div role="status">
              <h2>Listo, volvió tu correo</h2>
              <p>Tu acceso volvió a tu correo anterior y cerramos todas las sesiones que estaban abiertas.</p>
              <Link className="btn btn-primary btn-block" to="/login">Entrar con un código nuevo</Link>
            </div>
          ) : (
            <form onSubmit={deshacer} aria-busy={busy}>
              <h2>Deshacer el cambio</h2>
              {error ? <div className="error" role="alert">{error}</div> : null}
              <div className="field">
                <label htmlFor="rut-deshacer">RUT</label>
                <input id="rut-deshacer" value={rut} disabled={busy} required maxLength={14}
                       onChange={(e) => setRut(rutLegible(e.target.value))} placeholder="12.345.678-5" autoComplete="username" />
              </div>
              <div className="field">
                <label htmlFor="codigo-deshacer">Código del aviso</label>
                <input id="codigo-deshacer" className="code-input" value={codigo} disabled={busy} required
                       minLength={8} maxLength={8} placeholder="········" autoComplete="off"
                       onChange={(e) => setCodigo(e.target.value.toUpperCase().replace(/[^0-9A-Z]/g, "").slice(0, 8))} />
              </div>
              <button className="btn btn-primary btn-block" disabled={busy || codigo.length !== 8}>
                {busy ? <><span className="girando" /> Deshaciendo…</> : "Deshacer el cambio"}
              </button>
              <p className="hint">Si fuiste tú quien cambió el correo, no tienes que hacer nada.</p>
            </form>
          )}
        </div>
      </section>
    </div>
  );
}

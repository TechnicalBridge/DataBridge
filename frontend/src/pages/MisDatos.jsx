import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { cambiarRecordatorios, misDatos } from "../api/deudas";
import { rutLegible } from "../utils/formato";
import Cargando from "../components/Cargando";

/**
 * Lo que DataBridge sabe del deudor y de parte de quien.
 *
 * Sirve para confirmar que la cobranza es de verdad: si la empresa es la suya
 * y el correo es el suyo, el mensaje que le llego es legitimo. Los datos no se
 * editan aca porque los trae el acreedor; lo unico que decide el deudor son
 * los recordatorios.
 */
export default function MisDatos() {
  const [datos, setDatos] = useState(null);
  const [error, setError] = useState("");
  const [guardando, setGuardando] = useState(false);

  useEffect(() => {
    misDatos().then(setDatos).catch((err) => setError(err.message));
  }, []);

  async function recordatorios(quiere) {
    setGuardando(true);
    setError("");
    setDatos((antes) => ({ ...antes, recordatorios: quiere }));
    try {
      setDatos(await cambiarRecordatorios(quiere));
    } catch (err) {
      setDatos((antes) => ({ ...antes, recordatorios: !quiere }));
      setError(err.message);
    } finally {
      setGuardando(false);
    }
  }

  if (!datos) return error ? <div className="error">{error}</div> : <Cargando tarjetas={1} />;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Mis datos</h1>
          <p>Lo que tenemos registrado de ti, y quién nos lo entregó.</p>
        </div>
      </header>
      {error ? <div className="error">{error}</div> : null}

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <h2>Tus datos</h2>
          <table className="table">
            <tbody>
              <tr><th>Nombre</th><td>{datos.nombre}</td></tr>
              <tr><th>RUT</th><td>{rutLegible(datos.rut)}</td></tr>
              <tr><th>Correo</th><td>{datos.correo || "Sin correo registrado"}</td></tr>
              <tr><th>Teléfono</th><td>{datos.telefono || "Sin teléfono registrado"}</td></tr>
            </tbody>
          </table>
          <p className="hint">
            El correo y el teléfono se ven a medias para que nadie los lea en tu pantalla. Tú igual puedes
            reconocer si son los tuyos.
          </p>
        </div>

        <div className="card aparece" style={{ "--i": 2 }}>
          <h2>Quién te registró</h2>
          {datos.acreedores.map((a) => (
            <p key={a.rut} style={{ margin: "0 0 12px" }}>
              <b>{a.nombre}</b> (RUT {rutLegible(a.rut)}) te registró con {a.deudas === 1 ? "una deuda" : `${a.deudas} deudas`}
              {a.cobraPorSuCuenta ? <>, y <b>{a.cobraPorSuCuenta}</b> la cobra por su encargo</> : null}.
            </p>
          ))}
          <p className="hint">
            Si tu correo o tu teléfono están mal, avísale a {datos.acreedores[0]?.nombre || "tu acreedor"}. Nosotros
            no los cambiamos: llegan con su cartera. Si no reconoces la deuda, no pagues y comunícate directo con
            ellos.
          </p>
        </div>
      </div>

      <div className="card aparece" style={{ "--i": 3, marginTop: 16 }}>
        <label className="interruptor">
          <input type="checkbox" className="oculto" checked={datos.recordatorios} disabled={guardando}
                 onChange={(e) => recordatorios(e.target.checked)} />
          <span className="riel" aria-hidden="true" />
          <span>
            <b>Avisarme por correo {datos.diasAntes} días antes de cada cuota</b>
            <span className="sub">
              El correo trae un código para entrar y la fecha. No trae montos ni enlaces.
            </span>
          </span>
        </label>
      </div>

      <p className="hint">
        ¿Dudas sobre cómo funciona esto? <Link className="link-btn" to="/app/como-funciona">Cómo funciona</Link>
      </p>
    </div>
  );
}

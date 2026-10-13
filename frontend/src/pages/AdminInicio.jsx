import { useEffect, useState } from "react";
import { SERVICIOS, estadoDe } from "../api/admin";
import { useAuth } from "../store/authStore";
import { IconoAlerta, IconoCheck } from "../components/Iconos";

/**
 * La portada del panel de administracion: quien entro y si cada servicio le
 * responde. Es lo primero que ve el admin de DataBridge, y la prueba de que su
 * sesion llega a los tres servicios.
 */
export default function AdminInicio() {
  const { user } = useAuth();
  //  Por servicio: sin valor mientras se consulta, y despues "ok" o "caido".
  const [estados, setEstados] = useState({});

  function consultar() {
    setEstados({});
    SERVICIOS.forEach(({ tramo }) => {
      estadoDe(tramo)
        .then((r) => setEstados((e) => ({ ...e, [tramo]: r.ok ? "ok" : "caido" })))
        .catch(() => setEstados((e) => ({ ...e, [tramo]: "caido" })));
    });
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

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          <h3>Servicios</h3>
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
          <h3>Quién entra aquí</h3>
          <p style={{ marginTop: 0 }}>
            Solo el equipo de DataBridge. Una empresa usa su propio portal y ve solo su cartera, y sus
            clientes entran al portal de pago con su RUT y su código.
          </p>
          <p className="hint">
            Desde aquí no se ven los datos de ningún deudor.
          </p>
        </div>
      </div>
    </div>
  );
}

function Estado({ valor }) {
  if (valor === "ok") return <span className="badge badge-ok">Responde</span>;
  if (valor === "caido") return <span className="badge badge-peligro">No responde</span>;
  return <span className="badge badge-muted">Consultando…</span>;
}

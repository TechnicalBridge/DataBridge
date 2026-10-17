import { useEffect, useState } from "react";
import { SERVICIOS, reglasDe } from "../api/admin";

/**
 * Las reglas que estan rigiendo, de solo lectura: cada servicio arma las suyas
 * con su propia configuracion, y cada una dice de que variable del .env sale.
 * Un servicio que no responde no deja sin las reglas de los otros.
 */
export default function AdminReglas() {
  //  Por servicio: sin valor mientras se consulta; despues sus reglas, o "caido".
  const [reglas, setReglas] = useState({});

  function consultar() {
    setReglas({});
    SERVICIOS.forEach(({ tramo }) => {
      reglasDe(tramo)
        .then((r) => setReglas((todas) => ({ ...todas, [tramo]: r })))
        .catch(() => setReglas((todas) => ({ ...todas, [tramo]: "caido" })));
    });
  }

  useEffect(consultar, []);

  const consultando = SERVICIOS.some(({ tramo }) => !reglas[tramo]);

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Reglas</h1>
          <p>
            Lo que está rigiendo ahora en cada servicio. A la derecha de cada regla, la variable del
            {" "}<code>.env</code> que la cambia; cambiarla pide reiniciar ese servicio.
          </p>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={consultar} disabled={consultando}>
          Volver a consultar
        </button>
      </header>

      <div className="reglas">
        {SERVICIOS.map(({ tramo, nombre, servicio }, i) => (
          <section className="card aparece" style={{ "--i": i + 1 }} key={tramo} aria-labelledby={`reglas-${tramo}`}>
            <h3 id={`reglas-${tramo}`}>
              {nombre} <span className="reglas-servicio">{servicio}</span>
            </h3>
            <Bloque estado={reglas[tramo]} />
          </section>
        ))}
      </div>
    </div>
  );
}

function Bloque({ estado }) {
  if (!estado) {
    return <p className="hint" aria-live="polite">Consultando…</p>;
  }
  if (estado === "caido") {
    return (
      <p className="hint" aria-live="polite">
        <span className="badge badge-peligro">No responde</span>{" "}
        Sus reglas se ven cuando vuelva; las de los otros servicios siguen aquí.
      </p>
    );
  }
  return estado.grupos.map((grupo) => (
    <div key={grupo.nombre}>
      <h4 className="lista-grupo">{grupo.nombre}</h4>
      <div className="lista">
        {grupo.reglas.map((regla) => <Regla regla={regla} key={regla.que} />)}
      </div>
    </div>
  ));
}

function Regla({ regla }) {
  const lista = regla.lista || [];
  return (
    <div className="regla">
      <div className="regla-que">{regla.que}</div>
      <div className="regla-valor">
        {regla.valor}
        {lista.length > 0 && (
          <details>
            <summary>Ver los días</summary>
            <ul className="regla-lista">
              {lista.map((dia) => <li key={dia}>{dia}</li>)}
            </ul>
          </details>
        )}
        {regla.variable && regla.nota && <p className="hint">{regla.nota}</p>}
      </div>
      <div className="regla-origen">
        {regla.variable
          ? regla.variable.split(" · ").map((variable) => <code key={variable}>{variable}</code>)
          : <span className="regla-ley">{regla.nota}</span>}
      </div>
    </div>
  );
}

import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { reclamosAdmin } from "../api/admin";
import Paginas, { dePagina } from "../components/Paginas";
import { fechaHora, hoyEnChile, MOTIVOS_DISPUTA } from "../utils/formato";

const POR_PAGINA = 20;

function Lista({ titulo, filas, resueltos }) {
  const [pagina, setPagina] = useState(0);
  const { enPagina, filas: visibles } = dePagina(filas, pagina, POR_PAGINA);
  return (
    <section className="card" style={{ marginBottom: 16 }}>
      <h2>{titulo} ({filas.length.toLocaleString("es-CL")})</h2>
      {!filas.length ? <p className="hint">No hay reclamos en este grupo.</p> : (
        <>
          <div className="reclamos-lista">
            {visibles.map((r, i) => (
              <article className="reclamo-admin" key={`${r.deuda}-${r.reclamado}-${i}`}>
                <div>
                  <b>{r.empresa} · deuda {r.referencia}</b>
                  <span className="sub">{MOTIVOS_DISPUTA[r.motivo] ?? r.motivo ?? "Motivo no registrado"}</span>
                  {r.detalle ? <p className="reclamo-texto">{r.detalle}</p> : null}
                </div>
                <div>
                  <span className="sub">Reclamado: {r.reclamado ? fechaHora(r.reclamado) : "Sin fecha"}</span>
                  {resueltos ? (
                    <>
                      <b>{r.resultado === "retirado" ? "Retirada" : "Reanudada"}</b>
                      <span className="sub">Resuelto: {fechaHora(r.resuelto)}</span>
                      {r.nota ? <p className="reclamo-texto">Respuesta: {r.nota}</p> : null}
                    </>
                  ) : <b>{r.diasSinRespuesta} {r.diasSinRespuesta === 1 ? "día" : "días"} sin respuesta</b>}
                </div>
              </article>
            ))}
          </div>
          <Paginas total={filas.length} porPagina={POR_PAGINA} pagina={enPagina}
            alCambiar={setPagina} etiqueta={`Páginas de ${titulo.toLowerCase()}`} />
        </>
      )}
    </section>
  );
}

/** Seguimiento global: el administrador no resuelve; lo hace la empresa que cobra. */
export default function AdminReclamos() {
  const [desde, setDesde] = useState("");
  const [hasta, setHasta] = useState("");
  const [periodo, setPeriodo] = useState({ desde: "", hasta: "" });
  const [datos, setDatos] = useState(null);

  useEffect(() => {
    let vigente = true;
    reclamosAdmin(periodo.desde, periodo.hasta)
      .then((r) => { if (vigente) setDatos(r); })
      .catch(() => { if (vigente) setDatos("error"); });
    return () => { vigente = false; };
  }, [periodo]);

  function buscar(e) {
    e.preventDefault();
    setDatos(null);
    setPeriodo({ desde, hasta });
  }

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Reclamos</h1>
          <p>De todas las empresas. Las deudas en revisión no se cobran mientras la empresa responde.</p>
        </div>
        <Link className="btn btn-ghost btn-sm" to="/admin/operacion">Volver a Operación</Link>
      </header>
      <form className="card" onSubmit={buscar} style={{ marginBottom: 16 }}>
        <h2>Resueltos en un período</h2>
        <p className="hint">Sin fechas se muestra el mes actual en horario de Chile. Los abiertos siempre aparecen.</p>
        <div className="acciones-fila">
          <div className="field"><label htmlFor="reclamos-desde">Desde</label>
            <input id="reclamos-desde" type="date" value={desde} max={hasta || hoyEnChile()}
              onChange={(e) => setDesde(e.target.value)} /></div>
          <div className="field"><label htmlFor="reclamos-hasta">Hasta</label>
            <input id="reclamos-hasta" type="date" value={hasta} min={desde || undefined} max={hoyEnChile()}
              onChange={(e) => setHasta(e.target.value)} /></div>
          <button className="btn btn-primary btn-sm" type="submit">Consultar</button>
        </div>
      </form>
      {datos === "error" ? <p className="error" role="alert">No se pudieron consultar los reclamos.</p>
        : !datos ? <p role="status">Consultando reclamos…</p>
          : <>
            <Lista titulo="Sin respuesta" filas={datos.abiertos ?? []} />
            <Lista titulo="Resueltos" filas={datos.resueltos ?? []} resueltos />
          </>}
    </div>
  );
}

import { useMemo, useState } from "react";
import { guardarCampana } from "../api/campanas";
import { IconoAlerta } from "./Iconos";

const VACIA = {
  id: "", acreedor: "", nombre: "", inicio: "", fin: "", intentos: "3", cadencia: "1, 4, 11",
  descuento: { "1-30": "", "31-90": "", "91-120": "" },
};

/** Los tramos del contrato, por los días de mora del cargo impago más antiguo. */
export const TRAMOS = [["1-30", "1 a 30 días"], ["31-90", "31 a 90 días"], ["91-120", "Más de 90 días"]];

/** Un % de descuento del formulario: vacío es 0; si no, de 0 a 100 con hasta dos decimales. */
const porcentajeValido = (v) => v === "" || (/^\d{1,3}([.,]\d{1,2})?$/.test(v) && Number(v.replace(",", ".")) <= 100);

/** "1, 4, 11" -> { dias: [1, 4, 11] }, o el error. Las mismas reglas con que DataBridge la rechaza. */
function leerCadencia(texto) {
  const partes = texto.split(/[,;\s]+/).filter(Boolean);
  if (partes.length === 0) return { error: "Escribe al menos un día." };
  const dias = partes.map(Number);
  if (dias.some((d) => !Number.isInteger(d) || d <= 0)) return { error: "La cadencia son días enteros y mayores que cero." };
  if (dias.some((d, i) => i > 0 && d <= dias[i - 1])) return { error: "Cada día tiene que ser mayor que el anterior." };
  return { dias };
}

/**
 * Lo que la ley atrasa (art. 37 de la Ley 19.496): dos contactos con menos de 2 días
 * entre ellos, o un tercero dentro de la misma semana. No se rechaza: DataBridge lo corre.
 */
function advertencias(dias, intentos) {
  const usados = dias.slice(0, intentos);
  const avisos = [];
  if (usados.some((d, i) => i > 0 && d - usados[i - 1] < 2)) {
    avisos.push("Hay contactos con menos de 2 días entre ellos: la ley pide al menos 2.");
  }
  if (usados.some((d, i) => i > 1 && d - usados[i - 2] < 7)) {
    avisos.push("Hay 3 contactos dentro de una semana: la ley permite 2.");
  }
  if (intentos > dias.length) {
    avisos.push(`La cadencia tiene ${dias.length} ${dias.length === 1 ? "día" : "días"}: se harán ${dias.length} contactos, no ${intentos}.`);
  }
  return avisos;
}

/** Lo que muestra el formulario de una campaña que ya existe. */
function desde(campana) {
  return {
    id: campana.idExterno, acreedor: campana.acreedorRut, nombre: campana.nombre, inicio: campana.inicio || "",
    fin: campana.fin || "", intentos: String(campana.intentos), cadencia: (campana.cadenciaDias || []).join(", "),
    descuento: Object.fromEntries(TRAMOS.map(([t]) => [t, campana.descuentoPorTramo?.[t] != null
      ? String(campana.descuentoPorTramo[t]) : ""])),
  };
}

/**
 * Crear o cambiar una campaña. Lo usan la pantalla Campañas y el primer paso
 * de Cargar cartera, con las mismas reglas que las del contrato.
 *
 * Para cambiar otra campaña, se le da otra `key`: el formulario parte de cero.
 */
export default function FormCampana({ acreedores, editando = null, onGuardada, onCancelar, prefijo = "c" }) {
  const [form, setForm] = useState(() => (editando ? desde(editando) : { ...VACIA, acreedor: acreedores[0]?.rut || "" }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const cadencia = useMemo(() => leerCadencia(form.cadencia), [form.cadencia]);
  const intentos = Number(form.intentos);
  const intentosValidos = Number.isInteger(intentos) && intentos >= 1 && intentos <= 10;
  const fechasValidas = !form.inicio || !form.fin || form.fin >= form.inicio;
  const avisos = cadencia.dias && intentosValidos ? advertencias(cadencia.dias, intentos) : [];
  const descuentosValidos = TRAMOS.every(([t]) => porcentajeValido(form.descuento[t]));
  const acreedor = form.acreedor || acreedores[0]?.rut || "";
  const listo = form.nombre.trim() && acreedor && cadencia.dias && intentosValidos && fechasValidas && descuentosValidos;
  const id = (campo) => `${prefijo}-${campo}`;

  const campo = (nombre) => (e) => setForm((f) => ({ ...f, [nombre]: e.target.value }));

  async function guardar(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const guardada = await guardarCampana({
        id_externo: form.id.trim() || null,
        acreedor_rut: acreedor,
        nombre: form.nombre.trim(),
        inicio: form.inicio || null,
        fin: form.fin || null,
        canales: ["correo"],
        intentos,
        cadencia_dias: cadencia.dias,
        //  Vacío quita el descuento; lo que no pasa del máximo del acreedor lo revisa DataBridge.
        descuento_mora_por_tramo: Object.fromEntries(TRAMOS
          .filter(([t]) => form.descuento[t] !== "" && Number(form.descuento[t].replace(",", ".")) > 0)
          .map(([t]) => [t, Number(form.descuento[t].replace(",", "."))])),
      });
      await onGuardada?.(guardada);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={guardar}>
      {error ? <div className="error">{error}</div> : null}
      {acreedores.length === 0 && !editando ? (
        <div className="aviso-revision">
          <b>Primero, un mandato</b>
          <span>Para crear una campaña necesitas un mandato vigente: decláralo en Campañas, en Mandatos.</span>
        </div>
      ) : null}
      <div className="field">
        <label htmlFor={id("nombre")}>Nombre</label>
        <input id={id("nombre")} value={form.nombre} maxLength={120} onChange={campo("nombre")}
               placeholder="Arriendos octubre" required />
      </div>
      {acreedores.length > 1 || editando ? (
        <div className="field">
          <label htmlFor={id("acreedor")}>Acreedor</label>
          <select id={id("acreedor")} value={acreedor} onChange={campo("acreedor")}
                  disabled={Boolean(editando) || acreedores.length === 0}>
            {acreedores.length === 0 ? <option value="">Sin acreedores con mandato vigente</option> : null}
            {acreedores.map((a) => <option key={a.rut} value={a.rut}>{a.nombre}</option>)}
          </select>
        </div>
      ) : null}
      <div className="campos-2">
        <div className="field">
          <label htmlFor={id("inicio")}>Inicio</label>
          <input id={id("inicio")} type="date" value={form.inicio} onChange={campo("inicio")} />
        </div>
        <div className="field">
          <label htmlFor={id("fin")}>Fin</label>
          <input id={id("fin")} type="date" value={form.fin} min={form.inicio || undefined} onChange={campo("fin")}
                 required={Boolean(editando?.fin)} />
        </div>
      </div>
      {!fechasValidas ? <div className="error">El fin no puede ser antes del inicio.</div> : null}
      <div className="campos-2">
        <div className="field">
          <label htmlFor={id("intentos")}>Contactos</label>
          <input id={id("intentos")} type="number" min={1} max={10} step={1} value={form.intentos}
                 onChange={campo("intentos")} required />
        </div>
        <div className="field">
          <label htmlFor={id("cadencia")}>Días de contacto</label>
          <input id={id("cadencia")} value={form.cadencia} onChange={campo("cadencia")} inputMode="numeric"
                 placeholder="1, 4, 11, 25, 45" aria-describedby={id("cadencia-ayuda")} required />
        </div>
      </div>
      <p className="hint" id={id("cadencia-ayuda")} style={{ marginTop: -6, marginBottom: 14 }}>
        Los días se cuentan desde que la deuda entra a DataBridge. Canal: correo.
      </p>
      {!intentosValidos ? <div className="error">Los contactos van de 1 a 10.</div> : null}
      {cadencia.error ? <div className="error">{cadencia.error}</div> : null}
      {avisos.length > 0 ? (
        <div className="aviso bloque" role="status">
          <IconoAlerta />
          <div>
            <b>La ley va a atrasar algunos contactos</b>
            {avisos.map((a) => <span key={a} className="sub">{a}</span>)}
          </div>
        </div>
      ) : null}
      <fieldset className="campos-tramo">
        <legend>Descuento por pronto pago (opcional)</legend>
        <div className="campos-3">
          {TRAMOS.map(([t, etiqueta]) => (
            <div className="field" key={t}>
              <label htmlFor={id(`d-${t}`)}>{etiqueta}</label>
              <input id={id(`d-${t}`)} inputMode="decimal" placeholder="0" value={form.descuento[t]}
                     aria-describedby={id("descuento-ayuda")}
                     onChange={(e) => setForm((f) => ({ ...f, descuento: { ...f.descuento, [t]: e.target.value } }))} />
            </div>
          ))}
        </div>
        <p className="hint" id={id("descuento-ayuda")} style={{ marginTop: -6 }}>
          El % de los intereses de mora que se condona a quien paga toda la deuda de una vez, según lo
          atrasada que está. Nunca toca el capital, y no puede pasar lo que el acreedor autorizó.
        </p>
        {!descuentosValidos ? <div className="error">Cada descuento es un % de 0 a 100.</div> : null}
      </fieldset>
      {editando ? null : (
        <div className="field">
          <label htmlFor={id("id")}>Id en tu sistema (opcional)</label>
          <input id={id("id")} value={form.id} maxLength={64} onChange={campo("id")}
                 placeholder="Si lo dejas vacío, DataBridge le pone uno" />
        </div>
      )}
      <button className="btn btn-primary btn-block" disabled={busy || !listo}>
        {busy ? <><span className="girando" /> Guardando…</> : editando ? "Guardar cambios" : "Crear campaña"}
      </button>
      {onCancelar ? (
        <button type="button" className="btn btn-ghost btn-block" style={{ marginTop: 8 }} onClick={onCancelar}>
          Cancelar
        </button>
      ) : null}
    </form>
  );
}

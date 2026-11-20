# Rama de respaldo `backup/stable`

`backup/stable` guarda la última versión de `main` que se comprobó estable. Sirve de punto de restauración para demostraciones, evaluaciones y despliegues, mientras `main` sigue recibiendo cambios.

## Qué contiene

Hoy apunta a `f60c5c2` de `main`: las cinco suites de Java pasan (1.359 pruebas sin fallas, JDK 25) y el sistema levanta con `docker compose --profile app up -d --build --wait`.

## Cómo se actualiza

Solo después de un hito validado, nunca por un PR de trabajo:

1. Sobre `main`, correr `clean test` en cada servicio y compilar el portal (`npm run build` en `frontend`).
2. Levantar el sistema con `docker compose --profile app up -d --build --wait` y comprobar que los servicios quedan sanos.
3. Si todo pasa, avanzar la rama sin reescribir historia. Los administradores pueden avanzar la rama con un push directo o mediante un pull request de avance rápido (fast-forward) desde `main` hacia `backup/stable`:

```powershell
git fetch origin
git checkout backup/stable
git merge --ff-only <commit-validado>
git push origin backup/stable
```

*(Si las reglas de GitHub exigen pull request para toda modificación, se abre un PR de `main` a `backup/stable` tras verificar el hito).*

## Protección

En GitHub (*Settings → Branches / Rulesets*), para `backup/stable`:
- Bloquear `push --force` y la eliminación de la rama.
- Limitar el push directo a administradores del repositorio o requerir pull request con avance rápido desde `main`.

> **Nota:** Actualmente la regla de protección no se encuentra activa en el repositorio (`protected: false`). Su configuración formal en GitHub queda pendiente de activación por los administradores del repositorio; entretanto, el equipo respeta estas directrices por convención.

## Cómo restaurar el entorno

```powershell
git fetch origin
git checkout backup/stable
.\preparar-env.ps1
docker compose --profile app up -d --build --wait
```

Para volver `main` a este punto, abrir un PR con `git revert` de los cambios posteriores; no se reescribe `main`.

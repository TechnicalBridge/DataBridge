# Rama de respaldo `backup/stable`

`backup/stable` guarda la última versión de `main` que se comprobó estable. Sirve de punto de restauración para demostraciones, evaluaciones y despliegues, mientras `main` sigue recibiendo cambios.

## Qué contiene

Hoy apunta a `f60c5c2` de `main`: las cinco suites de Java pasan (1.359 pruebas sin fallas, JDK 25) y el sistema levanta con `docker compose`.

## Cómo se actualiza

Solo después de un hito validado, nunca por un PR de trabajo:

1. Sobre `main`, correr `clean test` en cada servicio y compilar el portal (`npm run build` en `frontend`).
2. Levantar el sistema con `docker compose up -d --build` y comprobar que los servicios quedan sanos.
3. Si todo pasa, avanzar la rama sin reescribir historia:

```powershell
git fetch origin
git checkout backup/stable
git merge --ff-only <commit-validado>
git push origin backup/stable
```

## Protección

Se activa en GitHub, en *Settings → Branches → Branch protection rules*, para `backup/stable`: bloquear `push --force` y el borrado de la rama, y exigir PR para modificarla.

## Cómo restaurar el entorno

```powershell
git fetch origin
git checkout backup/stable
.\preparar-env.ps1
docker compose up -d --build
```

Para volver `main` a este punto, abrir un PR con `git revert` de los cambios posteriores; no se reescribe `main`.

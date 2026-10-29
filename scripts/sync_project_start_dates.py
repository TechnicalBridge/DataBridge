#!/usr/bin/env python3
"""
Sincroniza y asigna automáticamente los issues al Project DataBridge (#3) de TechnicalBridge,
y puebla el campo 'Start date' de cada tarjeta con la fecha de creación (created_at) del issue.
"""
import json
import os
import subprocess
import sys
from datetime import datetime

ORGANIZATION = "TechnicalBridge"
PROJECT_NUMBER = 3
REPO = "TechnicalBridge/TB_web"

def run_graphql(query, variables=None):
    cmd = ["gh", "api", "graphql", "-f", f"query={query}"]
    if variables:
        for k, v in variables.items():
            if isinstance(v, int):
                cmd.extend(["-F", f"{k}={v}"])
            else:
                cmd.extend(["-f", f"{k}={v}"])
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"GraphQL Error: {res.stderr}", file=sys.stderr)
        return None
    try:
        data = json.loads(res.stdout)
        if "errors" in data:
            print(f"GraphQL Response Errors: {data['errors']}", file=sys.stderr)
        return data
    except Exception as e:
        print(f"JSON Parse Error: {e}\nOutput was: {res.stdout}", file=sys.stderr)
        return None

def main():
    print(f"=== Sincronizando Issues y Start Date con DataBridge (Project #{PROJECT_NUMBER}) ===")
    
    # 1. Obtener detalles del ProjectV2
    query_project = """
    query($org: String!, $number: Int!) {
      organization(login: $org) {
        projectV2(number: $number) {
          id
          title
          fields(first: 50) {
            nodes {
              ... on ProjectV2FieldCommon {
                id
                name
                dataType
              }
            }
          }
        }
      }
    }
    """
    proj_res = run_graphql(query_project, {"org": ORGANIZATION, "number": PROJECT_NUMBER})
    if not proj_res or "data" not in proj_res or not proj_res["data"].get("organization"):
        print("No se pudo obtener información del proyecto. Verifica permisos y autenticación.")
        sys.exit(1)
        
    project = proj_res["data"]["organization"]["projectV2"]
    project_id = project["id"]
    print(f"Proyecto encontrado: {project['title']} (ID: {project_id})")
    
    # 2. Localizar el campo 'Start date'
    start_date_field_id = None
    for field in project["fields"]["nodes"]:
        if field.get("dataType") == "DATE":
            name = field.get("name", "").lower()
            if "start" in name or "inicio" in name:
                start_date_field_id = field["id"]
                print(f"Campo Start date encontrado: '{field.get('name')}' (ID: {start_date_field_id})")
                break
                
    if not start_date_field_id:
        for field in project["fields"]["nodes"]:
            if field.get("dataType") == "DATE":
                start_date_field_id = field["id"]
                print(f"Fallback al primer campo DATE: '{field.get('name')}' (ID: {start_date_field_id})")
                break
                
    if not start_date_field_id:
        print("Error: No se encontró ningún campo de tipo DATE en el proyecto.")
        sys.exit(1)
        
    # 3. Obtener los issues del repositorio
    print(f"Consultando issues de {REPO}...")
    issues_cmd = ["gh", "api", f"repos/{REPO}/issues?state=all&per_page=100"]
    issues_res = subprocess.run(issues_cmd, capture_output=True, text=True)
    if issues_res.returncode != 0:
        print(f"Error al listar issues: {issues_res.stderr}")
        sys.exit(1)
        
    issues = [i for i in json.loads(issues_res.stdout) if "pull_request" not in i]
    print(f"Total de issues obtenidos: {len(issues)}")
    
    # 4. Asignar cada issue al proyecto y actualizar Start date si no está presente
    mutation_add_item = """
    mutation($projectId: ID!, $contentId: ID!) {
      addProjectV2ItemById(input: {projectId: $projectId, contentId: $contentId}) {
        item {
          id
        }
      }
    }
    """
    
    mutation_set_date = """
    mutation($projectId: ID!, $itemId: ID!, $fieldId: ID!, $date: Date!) {
      updateProjectV2ItemFieldValue(
        input: {
          projectId: $projectId
          itemId: $itemId
          fieldId: $fieldId
          value: {
            date: $date
          }
        }
      ) {
        projectV2Item {
          id
        }
      }
    }
    """
    
    for iss in issues:
        iss_num = iss["number"]
        node_id = iss["node_id"]
        created_at_raw = iss["created_at"] # e.g. 2026-10-23T02:39:22Z
        created_date = created_at_raw.split("T")[0]
        
        # Agregar/obtener item en el project
        add_res = run_graphql(mutation_add_item, {"projectId": project_id, "contentId": node_id})
        if not add_res or "data" not in add_res or not add_res["data"].get("addProjectV2ItemById"):
            print(f"Issue #{iss_num}: No se pudo vincular al proyecto.")
            continue
            
        item_id = add_res["data"]["addProjectV2ItemById"]["item"]["id"]
        
        # Asignar Start date
        set_res = run_graphql(mutation_set_date, {
            "projectId": project_id,
            "itemId": item_id,
            "fieldId": start_date_field_id,
            "date": created_date
        })
        if set_res and "data" in set_res and set_res["data"].get("updateProjectV2ItemFieldValue"):
            print(f"✓ Issue #{iss_num} -> Project Item: {item_id} | Start date: {created_date}")
        else:
            print(f"✗ Issue #{iss_num}: Falló al asignar Start date ({created_date})")

    print("=== Proceso de sincronización finalizado con éxito ===")

if __name__ == "__main__":
    main()

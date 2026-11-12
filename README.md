# ACA_tcp

Two container applications built with Java 25. They are designed for Azure Container Apps (ACA), but can also run with Podman or Docker alone.

| | Container app A (`app-a/`) | Container app B (`app-b/`) |
| --- | --- | --- |
| Role | TCP server that echoes each message with its listening port | HTTP API that connects to app A on the port specified by a query parameter, sends a message and returns the reply |
| Listening ports | TCP `18192` / `28192` / `38192` | HTTP `8080` (context path `/api/tcp`) |
| Configuration | None (ports are fixed) | Environment variable `TCP_SERVER_HOST` (hostname of app A) |

```text
client --HTTP GET /api/tcp?port=N--> app B (:8080) --TCP "Hello N, how are you?"--> app A (:N)
       <------- 200 + reply --------             <-- "Acknowledged ... sent from N!" --
```

App A uses only JDK standard libraries and virtual threads. App B uses Micronaut 5 HTTP Server (Netty) with compile-time dependency injection; blocking TCP calls run on Micronaut's blocking executor (virtual threads on Java 25), not on the Netty event loop. JUnit is used for tests only.

## Directory structure

```text
.
├── pom.xml          Parent POM (builds app-a and app-b)
├── app-a/           Container app A (Dockerfile: EXPOSE 18192 28192 38192)
├── app-b/           Container app B (Dockerfile: EXPOSE 8080)
├── azure.yaml       Azure Developer CLI (azd) definition (services app-a / app-b)
├── .github/         GitHub Actions workflow for automatic deployment (workflows/azure-dev.yml)
└── infra/           Bicep for Azure Container Apps (main.bicep, modules/, main.parameters.json for azd, main.sample.bicepparam for az)
```

## Specification

### Container app A

- Listens on the fixed TCP ports `18192`, `28192` and `38192`. It starts accepting requests only after it has bound all three ports. If binding any port fails, it exits with a startup error.
- The `Dockerfile` exposes all three ports with `EXPOSE`.
- When a message arrives on a port, the server returns the following string. `{port}` is the listening port on app A that received the message.

  `Acknowledged {request message} This message is sent from {port}!`

- The protocol uses UTF-8, with one message per line (LF; CRLF is also accepted). Replies are also one line, terminated by LF. Multiple messages can be sent over one connection. A line can be up to 8 KiB. The server closes the connection after 60 seconds without activity.

```console
$ echo "Hello" | nc -w 2 localhost 28192
Acknowledged Hello This message is sent from 28192!
```

### Container app B

- Uses a Micronaut controller, with server settings in `app-b/src/main/resources/application.properties`. Micronaut manages startup and graceful shutdown.
- Listens on HTTP `8080` with the context path `/api/tcp` (`/api/tcp/` is also accepted).
- For `GET /api/tcp?port={port}`, it connects to app A on `{port}`, sends `Hello {port}, how are you?`, and returns app A's reply as the response body (`text/plain; charset=utf-8`).
- The destination hostname is set with an environment variable. The TCP connection timeout is 3 seconds, and the reply timeout is 5 seconds.

| Environment variable | Default | Description |
| --- | --- | --- |
| `TCP_SERVER_HOST` | `localhost` | Hostname or IP address of app A (uses the default if unset or blank) |

| HTTP status | Condition |
| --- | --- |
| 200 | A reply was received from app A |
| 400 | `port` is missing or is not an integer from 1 to 65535 |
| 404 | Path is not `/api/tcp` or `/api/tcp/` |
| 405 | Method is not GET (`Allow: GET`) |
| 502 | Cannot connect to app A, resolve its hostname, or receive a reply before the connection closes |
| 504 | Connection to app A or waiting for its reply timed out |
| 500 | Unexpected error |

## Prerequisites

- JDK 25
- Maven (verified with 3.10.0)
- Podman or Docker (to run the applications in containers)

## Build and test

If the default `java` is not version 25, set `JAVA_HOME` to your JDK 25 installation.

```bash
export JAVA_HOME=/path/to/jdk-25
mvn -B -ntp clean verify
```

This creates `app-a/target/app.jar` and `app-b/target/app.jar`. App B's executable JAR includes its runtime dependencies, so the existing Dockerfile and `java -jar` commands work unchanged. Tests cover app A's echo behaviour (three ports, multiple lines, CRLF and clients that connect and then disconnect, among other cases), and app B's Micronaut configuration, query validation, status mapping and TCP client.

## Run locally (JAR)

Use two terminals.

```bash
# 1) Container app A
java -jar app-a/target/app.jar

# 2) Container app B (same machine as A, so TCP_SERVER_HOST is localhost)
TCP_SERVER_HOST=localhost java -jar app-b/target/app.jar
```

```console
$ curl "http://localhost:8080/api/tcp?port=18192"
Acknowledged Hello 18192, how are you? This message is sent from 18192!
```

## Run in containers (Podman / Docker)

The instructions have been tested with Podman. With Docker, replace `podman` with `docker`.

```bash
# Build the images (the Dockerfiles skip tests, so run mvn verify as shown above)
podman build -t aca-tcp-a:local app-a
podman build -t aca-tcp-b:local app-b

# Create a network so the containers can reach each other by name, then start them
podman network create aca-tcp-net
podman run -d --name app-a --network aca-tcp-net aca-tcp-a:local
podman run -d --name app-b --network aca-tcp-net -e TCP_SERVER_HOST=app-a -p 8080:8080 aca-tcp-b:local

# Call the API
curl "http://localhost:8080/api/tcp?port=18192"
curl "http://localhost:8080/api/tcp?port=28192"
curl "http://localhost:8080/api/tcp?port=38192"

# Clean up
podman rm -f app-a app-b && podman network rm aca-tcp-net
```

Example output:

```console
$ curl "http://localhost:8080/api/tcp?port=28192"
Acknowledged Hello 28192, how are you? This message is sent from 28192!

$ curl -i "http://localhost:8080/api/tcp?port=abc"
HTTP/1.1 400 Bad Request
...
Query parameter 'port' is required and must be an integer from 1 to 65535

$ curl "http://localhost:8080/api/tcp?port=12345"
Bad Gateway: cannot talk to app-a:12345 (ConnectException: Connection refused (connect failed))
```

The images use a multi-stage build (`maven:3-eclipse-temurin-25` to build, then `eclipse-temurin:25-jre` to run) and run as a non-root user (UID 10001).

## Deploy to Azure (Bicep)

The Bicep files in `infra/` deploy A and B to Container Apps environments in separate VNets. The template runs at subscription scope and also creates the resource group. The container apps only run images stored in Azure Container Registry (ACR) in the same resource group, not images from Docker Hub (see [Image handling](#image-handling)). To provision, build images, push them to ACR and deploy with one command, see [Deploy with Azure Developer CLI (azd)](#deploy-with-azure-developer-cli-azd).

> **A live deployment and connectivity test have not been performed.** Checks so far include `az bicep build` (no warnings), `az deployment sub validate`, `az deployment sub what-if` (no errors) and `azd provision --preview` (no errors). Items that are not documented in Microsoft Learn and need a live check are listed under [Items to verify in a live environment](#items-to-verify-in-a-live-environment).

### Deployment topology diagram

Open the [deployment topology with Azure icons (draw.io)](docs/deployment-topology.drawio) in diagrams.net or VS Code with Draw.io Integration. Page 1 shows communication and DNS; page 2 shows the ACR build, authentication and monitoring.

The diagram shows the design from the Bicep files in `infra/` and `azure.yaml`; it is not the result of a live deployment. It also shows items that have not been verified, including TCP connectivity through the Private Endpoint. The [official Microsoft Azure SVG icons](https://learn.microsoft.com/azure/architecture/icons/) are embedded in the file, so no external images need to be downloaded.

### Architecture

```text
Internet
   |  HTTPS
   v
+--- VNET B (10.20.0.0/16) ------------------------------------------+
|  snet-b  (10.20.0.0/24)                                            |
|    ACA environment B (external)                                    |
|      app-b : HTTP 8080                                             |
|         |                                                          |
|         |  TCP 18192 to the app-a FQDN                             |
|         v                                                          |
|  snet-pe (10.20.1.0/24)                                            |
|    Private Endpoint   <-- Private DNS Zone (linked to VNET B)      |
+----------|---------------------------------------------------------+
           |  Private Link
           v
+--- VNET A (10.10.0.0/16) ------------------------------------------+
|  snet-a  (10.10.0.0/24)                                            |
|    ACA environment A (internal, public network access disabled)    |
|      app-a : TCP 18192                                             |
+--------------------------------------------------------------------+

Logs: both environments -> diagnostic settings -> Log Analytics workspace (Azure Monitor)
```

| | app-a | app-b |
| --- | --- | --- |
| VNet / subnet | `10.10.0.0/16` / `10.10.0.0/24` | `10.20.0.0/16` / `10.20.0.0/24` (also creates `10.20.1.0/24` for the Private Endpoint) |
| Container Apps environment | Internal (reachable only from within the VNet). Public network access is disabled | External (reachable from the internet) |
| Ingress | TCP `18192` (VNet only) | HTTP `8080` (reachable over HTTPS from the internet) |
| Scale | Minimum 1 / maximum 1 (no scale rule) | Minimum 1 / maximum 1 (no scale rule) |
| Image | Image in ACR (bootstrap image only for the first provision) | Image in ACR (same as app-a) |
| Environment variable | None | `TCP_SERVER_HOST` = app A's FQDN (`app-a.<default domain of environment A>`) |

App B resolves app A's FQDN and connects to app A on port `18192` through the Private Endpoint. The Private DNS Zone is linked to VNet B.

### Image handling

The container apps only run images stored in ACR in the same resource group, not images from Docker Hub. ACR is created in the same deployment, so it does not yet contain images during provisioning. The process therefore has three steps:

1. **Provision** (`azd provision` or `az deployment sub create`): Create ACR, a managed identity (`AcrPull`), the VNets, environments and apps. The apps start with a bootstrap image (`bootstrapImage`, which defaults to the public Microsoft Container Registry image `mcr.microsoft.com/azuredocs/containerapps-helloworld:latest`).
2. **Build and push to ACR** (`azd deploy` or `az acr build`): Send the source to ACR Tasks, build it there and store the resulting images in ACR. Docker or Podman is not needed locally.
3. **Update the apps**: Change the app images to the ACR images and create new revisions. The apps pull from ACR using the managed identity (ACR admin user and anonymous pull are disabled).

The bootstrap image is used only until step 3. `appAImage` / `appBImage` accept only images in ACR. If you specify `app-a:v1` as `repository:tag`, the ACR login server is added automatically. During reprovisioning, azd automatically preserves the current image (from ACR); with `az`, use `appAExists` / `appBExists` (see [Parameters](#parameters)).

### Resources created

`<suffix>` is `nameSuffix` (by default, a six-character value that looks random and is derived from the subscription, environment name and region). You can change all names with parameters.

| Resource | Default name | Notes |
| --- | --- | --- |
| Resource group | `rg-acatcp-<suffix>` | Contains all the resources below |
| Log Analytics workspace | `log-acatcp-<suffix>` | Log destination (Azure Monitor); 30-day retention |
| VNet A and subnet | `vnet-a-<suffix>` / `snet-a-<suffix>` | Subnet is delegated to `Microsoft.App/environments` |
| VNet B and subnets | `vnet-b-<suffix>` / `snet-b-<suffix>`, `snet-pe-<suffix>` | `snet-pe` is for the Private Endpoint and is not delegated |
| Container Apps environments A / B | `cae-a-<suffix>` / `cae-b-<suffix>` | Workload profiles (Consumption); logs go to Azure Monitor |
| Container apps | `app-a` / `app-b` | 0.5 vCPU / 1 GiB. The `azd-service-name` tag identifies the azd deployment target |
| Container Registry | `acr<hash>` | Basic. Stores container app images (pushed by `azd deploy` / `az acr build`). Admin user and anonymous pull are disabled |
| User-assigned managed identity | `id-acatcp-<suffix>` | Assigned to both container apps and used to pull images from ACR |
| Role assignment | `AcrPull` (ACR scope) | Assigned to the managed identity above |
| Private Endpoint | `pe-app-a-<suffix>` | Connects from VNet B to environment A (group ID `managedEnvironments`) |
| Private DNS Zone | `privatelink.<region>.azurecontainerapps.io` | Linked to VNet B; DNS zone group automatically registers the A record |
| Diagnostic settings | `send-to-log-analytics` (environments A / B) | Sends all logs (`allLogs`) to the Log Analytics workspace |

### Prerequisites

- Azure CLI and Bicep (`az bicep install`). Verified with Azure CLI 2.90.0 / Bicep 0.49.2.
- Permission to create the resource group and resources in the subscription (the deployment uses subscription scope). As the deployment also creates an ACR role assignment (`AcrPull`), you need **Owner, or both Contributor and User Access Administrator**. Contributor alone cannot create the role assignment.
- Register the resource providers `Microsoft.App`, `Microsoft.Network`, `Microsoft.OperationalInsights`, `Microsoft.Insights`, `Microsoft.ContainerRegistry` and `Microsoft.ManagedIdentity` (`az provider register --namespace <name>`).
- Use Azure CLI's `az acr build` to build images. This uses ACR Tasks, so Docker or Podman is not needed locally; it also produces `linux/amd64` images on Apple Silicon Macs. ACR Tasks must be allowed for the subscription.

### Deployment

ACR is created in the same deployment, so use these three steps. With azd, `azd up` runs them in one command.

#### 1. Create infrastructure and apps

```bash
# Defaults: names are fixed values that look random and are derived from the subscription, environment name and region. The region is the same as --location.
az deployment sub create --name acatcp --location japaneast --template-file infra/main.bicep

# To set the region and names
az deployment sub create --name acatcp --location japaneast --template-file infra/main.bicep \
  --parameters location=japaneast resourceGroupName=rg-acatcp \
               appAVnetName=vnet-a appASubnetName=snet-a appBVnetName=vnet-b appBSubnetName=snet-b

# To use a parameter file (example: infra/main.sample.bicepparam; --template-file is not needed)
az deployment sub create --name acatcp --location japaneast --parameters infra/main.sample.bicepparam
```

`--location` is the region where deployment metadata is stored. If you omit the `location` parameter, it is also used as the region for the resources. The sample file is named `main.sample.bicepparam`, not `main.bicepparam`, because azd reads `main.bicepparam` before `main.parameters.json`.

At this point, the apps use the bootstrap image (`bootstrapImage`). ACR is still empty, so do not set `appAImage` / `appBImage` yet.

#### 2. Build images and push to ACR

```bash
ACR=$(az deployment sub show --name acatcp --query properties.outputs.containerRegistryName.value -o tsv)
az acr build --registry "$ACR" --image app-a:v1 app-a
az acr build --registry "$ACR" --image app-b:v1 app-b
```

`az acr build` sends the source in each directory to ACR Tasks. ACR Tasks builds it with the Dockerfile (including the Maven build inside the container) and pushes the resulting image to ACR.

#### 3. Update apps to use ACR images

Deploy again with the same parameters as step 1, adding the two image parameters.

```bash
az deployment sub create --name acatcp --location japaneast --template-file infra/main.bicep \
  --parameters appAImage=app-a:v1 appBImage=app-b:v1
```

The apps switch to the ACR images and create new revisions (pulling from ACR with the managed identity). For later `az deployment sub create` runs, keep specifying the images or set `appAExists=true appBExists=true` to keep the current images. If you do neither, the apps will switch back to the bootstrap image.

After step 3, get app B's URL from the outputs and call it. If everything works as expected, the reply will look like this (not verified in a live environment):

```bash
az deployment sub show --name acatcp --query properties.outputs
curl "$(az deployment sub show --name acatcp --query properties.outputs.appBUrl.value -o tsv)"
# Acknowledged Hello 18192, how are you? This message is sent from 18192!
```

Use `what-if` to review changes before deployment.

```bash
az deployment sub what-if --name acatcp --location japaneast --template-file infra/main.bicep
```

When you no longer need the resources, delete the resource group.

```bash
az group delete --name "$(az deployment sub show --name acatcp --query properties.outputs.resourceGroupName.value -o tsv)"
```

Log Analytics workspaces are soft-deleted. To recreate one with the same name within 14 days, either permanently delete the workspace before deleting the resource group (`az monitor log-analytics workspace delete --resource-group <resource group> --workspace-name <name> --force --yes`), or change `environmentName` / `nameSuffix`.

### Parameters

All parameters are optional. Default names include `nameSuffix`.

| Parameter | Default | Description |
| --- | --- | --- |
| `environmentName` | `acatcp` | Environment name. Used to generate `nameSuffix` and in the `azd-env-name` resource group tag (azd: `AZURE_ENV_NAME`) |
| `location` | Value of `--location` | Resource region |
| `nameSuffix` | `take(uniqueString(subscription ID, environmentName, location), 6)` | Suffix for default names (3-12 characters). It looks random, but the same inputs always produce the same value |
| `resourceGroupName` | `rg-acatcp-<nameSuffix>` | Resource group name |
| `appAVnetName` | `vnet-a-<nameSuffix>` | VNet name for app-a |
| `appASubnetName` | `snet-a-<nameSuffix>` | Subnet name for the app-a environment |
| `appBVnetName` | `vnet-b-<nameSuffix>` | VNet name for app-b |
| `appBSubnetName` | `snet-b-<nameSuffix>` | Subnet name for the app-b environment |
| `appBPrivateEndpointSubnetName` | `snet-pe-<nameSuffix>` | Subnet name for the Private Endpoint (in VNet B) |
| `containerRegistryName` | `acr<hash>` | ACR name (5-50 lowercase letters and numbers; globally unique in Azure) |
| `appAVnetAddressPrefix` | `10.10.0.0/16` | Address space for app-a's VNet. The subnet uses the first `/24` |
| `appBVnetAddressPrefix` | `10.20.0.0/16` | Address space for app-b's VNet (must not overlap with VNet A). The environment subnet uses the first `/24`; the Private Endpoint subnet uses the second `/24` |
| `bootstrapImage` | `mcr.microsoft.com/azuredocs/containerapps-helloworld:latest` | Bootstrap image used only on the first provision, before images are available in ACR (public image from Microsoft Container Registry, not Docker Hub) |
| `appAImage` / `appBImage` | Empty | Image in ACR. Specify `repository:tag`, such as `app-a:v1`, and the ACR login server is added automatically (a full reference including the login server is also accepted). Images from other registries are not supported. If empty, the current image is kept when `appAExists` / `appBExists` is `true`; otherwise `bootstrapImage` is used. With azd, these parameters are set by `azd deploy`, so you normally do not need to specify them |
| `appAExists` / `appBExists` | `false` | When `true` and `appAImage` / `appBImage` is empty, keep the current image of the existing app. azd sets these automatically, so you do not need to set them when using azd |
| `tags` | `{}` | Tags to add to all resources (`azd-env-name` is added automatically) |

- **Default names are stable**: `nameSuffix` is generated with `uniqueString`, so the same subscription, environment name and region always produce the same names. Running the same command again updates the same resources. This allows you to rerun azd (`azd provision` / `azd up`) and use `what-if`.
- **Recreate with different names**: Change `environmentName` or `nameSuffix`. A Log Analytics workspace cannot be reused under the same name for 14 days after deletion (soft delete). Recreating it immediately after deleting the resource group will fail. Permanently delete it as described above, or use a different name.
- **Address spaces and tags**: The azd file `infra/main.parameters.json` does not include `appAVnetAddressPrefix`, `appBVnetAddressPrefix` or `tags`. To change them, edit the Bicep defaults or that file.

### Logs (Azure Monitor)

- Both environments set the log destination (`appLogsConfiguration.destination`) to `azure-monitor`. Each has a diagnostic setting named `send-to-log-analytics`, which sends all logs (`allLogs`) to the Log Analytics workspace in the same resource group (`log-acatcp-<suffix>`).
- App standard output / standard error is stored in the `ContainerAppConsoleLogs` table; system logs are in `ContainerAppSystemLogs`. In the workspace's **Logs**, you can run a query such as:

```kusto
ContainerAppConsoleLogs
| where ContainerAppName in ("app-a", "app-b")
| project TimeGenerated, ContainerAppName, Log
| order by TimeGenerated desc
```

- Logs can take up to 90 minutes to appear after the diagnostic setting is created. Tables are created when the first log arrives ([Diagnostic settings in Azure Monitor](https://learn.microsoft.com/azure/azure-monitor/platform/diagnostic-settings)).

### Items to verify in a live environment

The following items are not documented in Microsoft Learn and need a proof of concept in a live environment or confirmation through a support request.

1. **Whether TCP `18192` works through the Private Endpoint**

   The Learn guide for [using a private endpoint with an Azure Container Apps environment](https://learn.microsoft.com/azure/container-apps/how-to-use-private-endpoint) uses an HTTP app as its example. It does not say whether non-HTTP TCP ingress can be reached through a Private Endpoint. From a VM in VNet B, try `nc -vz app-a.<default domain of environment A> 18192`, or check whether `curl "https://<app B FQDN>/api/tcp?port=18192"` returns 200. If it does not work, switch to peering the two VNets. In that case, the Private DNS Zone would have the same name as environment A's default domain and a `*` A record pointing to environment A's static IP.

2. **Name resolution**

   The Learn guide says `app-a.<default domain>` resolves through a public DNS CNAME to `<first label of default domain>.privatelink.<region>.azurecontainerapps.io`, and then to an A record (the Private Endpoint IP) in the Private DNS Zone. From VNet B, run `nslookup app-a.<default domain of environment A>` and check that it returns the Private Endpoint IP (`10.20.1.x`).

   The Learn guide adds the A record manually rather than using a DNS zone group. This Bicep relies on the DNS zone group to register the A record automatically, but that has not been verified. If it is not registered, add it as follows (replace `<resource group>` and `<suffix>`):

   ```bash
   PE_IP=$(az network private-endpoint show --resource-group <resource group> --name pe-app-a-<suffix> \
     --query 'customDnsConfigs[0].ipAddresses[0]' --output tsv)
   RECORD=$(az containerapp env show --resource-group <resource group> --name cae-a-<suffix> \
     --query properties.defaultDomain --output tsv | sed 's/\..*//')
   az network private-dns record-set a add-record --resource-group <resource group> \
     --zone-name privatelink.<region>.azurecontainerapps.io \
     --record-set-name "$RECORD" --ipv4-address "$PE_IP"
   ```

3. **Confirm app A is not reachable from the internet**

   App A uses `external: true` so that app B, which is in a separate environment, can call it (`false` would allow access only from the same environment). Since environment A is internal, it should only be reachable from within the VNet (including through the Private Endpoint). Also confirm that it cannot be reached from the internet.

4. **First provision with the bootstrap image**

   The apps start with the bootstrap image (`bootstrapImage`) on the first provision. This image listens on port `80`, which does not match the ingress target ports (TCP `18192` for A and HTTP `8080` for B); therefore, the default health probes (TCP connections to the target port) will fail. Official azd templates also use this bootstrap image, but it has not been checked whether the first deployment succeeds with this setup, especially app A's TCP ingress. If it fails, ACR and the VNets should already have been created. Build and push the images to ACR with `az acr build` (find the ACR name with `az acr list --resource-group <resource group>`), then set `appAImage` / `appBImage` (with azd, use `azd env set AZURE_APP_A_IMAGE app-a:v1` and `azd env set AZURE_APP_B_IMAGE app-b:v1`) and deploy again. This avoids using the bootstrap image.

### Notes

- **Only port `18192` is exposed by app A** (as specified). If B uses `port=28192` or `38192`, it cannot reach A and returns 502 / 504. To use these ports too, add `additionalPortMappings` to the ingress in `infra/modules/container-app.bicep` (see `app-a.yaml` below for the format).
- **Private Endpoint subnet**: A Private Endpoint cannot be placed in a subnet delegated to `Microsoft.App/environments`, so VNet B has another `/24` subnet separate from the environment subnet.
- **Additional resources and costs**: ACA creates a managed resource group for each environment (names start with `ME_`) and places a Standard Load Balancer there (external environments also have a public IP for ingress). The Standard static public IP for egress is also billed separately from Container Apps usage ([Managed resources](https://learn.microsoft.com/azure/container-apps/custom-virtual-networks#managed-resources)). The Private Endpoint also has a charge. Do not change the managed resource group.
- **Base images are pulled from Docker Hub**: The Dockerfile `FROM` images (`maven:3-eclipse-temurin-25`, `eclipse-temurin:25-jre`) are public Docker Hub images. They are pulled only at build time (by ACR Tasks or a local build); the images run by Container Apps are only from ACR. If a build fails due to anonymous pull limits, import the base images into ACR with `az acr import` and update the `FROM` references.
- **No NSGs are created**. Add them if needed.

## Deploy with Azure Developer CLI (azd)

The [Azure Developer CLI (azd)](https://learn.microsoft.com/azure/developer/azure-developer-cli/overview) can provision with Bicep, build and push images to ACR, and deploy to Container Apps (which pulls from ACR) with a single `azd up` command. You can also configure automatic deployment with GitHub Actions using `azd pipeline config` (see [Automatic deployment (GitHub Actions)](#automatic-deployment-github-actions)). Verified with azd 1.37.3.

> **A live deployment with `azd up` has not been performed.** Checks so far include `azd provision --preview` (including name overrides), `azd package` and schema validation for `azure.yaml`; all completed without errors. Items that need a live check are listed at the end of this section.

### End-to-end flow

`azd up` runs the following steps. Images come only from ACR, not Docker Hub.

| Step | Stage | Description |
| --- | --- | --- |
| 1 | `azd package` | Builds images locally only when `remoteBuild: false`. With the default `remoteBuild: true`, it does not build anything locally (`No artifacts were found` is displayed) |
| 2 | `azd provision` | Deploys `infra/main.bicep` and creates ACR, a managed identity (`AcrPull`), VNets, Container Apps environments and apps. Apps start with the bootstrap image (`bootstrapImage`) |
| 3 | `azd deploy` (build images) | Sends the source to ACR Tasks and builds each app with its Dockerfile (including Maven inside the container). Docker / Podman is not needed locally |
| 4 | `azd deploy` (push to ACR) | Stores the built images in ACR (repository `acatcp/<service name>-<azd environment name>`, tag `azd-deploy-<UNIX time>`) |
| 5 | `azd deploy` (deploy to Container Apps) | Switches the apps to the ACR images and creates new revisions. The apps pull from ACR using the managed identity (`AcrPull`) |
| 6 | `azd pipeline config` (once) | Configures automatic deployment with GitHub Actions. Later, a push to `main` runs steps 2-5 automatically |

Provisioning (step 2) happens before the build because it creates the ACR that will store the images. Steps 3-5, from building through deployment to Container Apps, run together inside `azd deploy`.

### azd files

| File | Description |
| --- | --- |
| `azure.yaml` | Defines services `app-a` / `app-b`. The target is `host: containerapp`; each Dockerfile is used to build (`language: docker`). With `host: containerapp`, either `language` or `image` is required. Images are built with ACR Tasks (`remoteBuild: true`), so Docker / Podman is not needed locally |
| `infra/main.bicep` | Entry point for provisioning. Includes outputs used by azd (`AZURE_RESOURCE_GROUP`, `AZURE_CONTAINER_REGISTRY_ENDPOINT`) and tags to find deployment targets (`azd-env-name` on the resource group and `azd-service-name` on container apps) |
| `infra/main.parameters.json` | Passes azd environment values (`AZURE_ENV_NAME`, `AZURE_LOCATION`, etc.) to Bicep parameters (see table below) |
| `infra/modules/registry.bicep` | ACR, user-assigned managed identity and `AcrPull` role assignment |
| `infra/modules/existing-container-image.bicep` | Module to keep the deployed image during reprovisioning |
| `.github/workflows/azure-dev.yml` | Automatic deployment workflow (see [Automatic deployment (GitHub Actions)](#automatic-deployment-github-actions)) |

`infra/main.sample.bicepparam` is an example for `az deployment sub create`, not azd. It is named this way because azd does not read `main.parameters.json` when `main.bicepparam` exists.

### Command flow

| Command | Description |
| --- | --- |
| `azd provision` | Deploys `infra/main.bicep`. app-a / app-b start with the bootstrap image (`bootstrapImage`) |
| `azd deploy` | Builds app-a / app-b with ACR Tasks, pushes them to ACR, switches the container apps to the ACR images and creates new revisions |
| `azd up` | Runs `azd package`, `azd provision` and `azd deploy` in order |
| `azd pipeline config` | Configures automatic deployment with GitHub Actions (see [Automatic deployment (GitHub Actions)](#automatic-deployment-github-actions)) |
| `azd provision --preview` | Shows the planned changes without creating resources |
| `azd down` | Deletes the resource group. Add `--purge` to permanently delete soft-deleted resources such as the Log Analytics workspace |

After `azd deploy`, running `azd provision` again keeps the ACR images. azd automatically sets `SERVICE_APP_A_RESOURCE_EXISTS` / `SERVICE_APP_B_RESOURCE_EXISTS`; Bicep uses these values to keep the current images of existing apps.

### Prerequisites

- Install azd (for example, on macOS, `brew tap azure/azd && brew install azd`; see [installation instructions](https://learn.microsoft.com/azure/developer/azure-developer-cli/install-azd)).
- Sign in to Azure (`azd auth login`).
- Permissions and resource providers are the same as the Bicep [prerequisites](#prerequisites-1): Owner, or both Contributor and User Access Administrator.
- Local Docker / Podman is not needed (images are built with ACR Tasks).
- To configure automatic deployment, install GitHub CLI (`gh`) and use a GitHub repository.

### Usage

```bash
azd auth login

# Create an environment (name and region; if the subscription is omitted, azd asks you to choose one when running azd up)
azd env new acatcp-dev --location japaneast

# Provision, build images, push to ACR and deploy (you can also run azd provision and azd deploy separately)
azd up
```

After deployment, get app B's URL from the outputs and call it. If everything works as expected, the reply will look like this (not verified in a live environment):

```bash
azd env get-values
curl "$(azd env get-value appBUrl)"
# Acknowledged Hello 18192, how are you? This message is sent from 18192!
```

For logs, see the Log Analytics workspace as described in [Logs (Azure Monitor)](#logs-azure-monitor).

When you no longer need the resources, delete them with `azd down --purge`. Add `--force` to skip the confirmation prompt.

### Set names and other values

With azd, set Bicep parameters through azd environment values (environment variables). Set the region, resource group name, VNet names and subnet names with `azd env set` **before the first `azd up` (or `azd provision`)**. Values you do not set use the defaults (names that look random). Changing them after the first provision may cause deployment to fail or create different resources.

```bash
azd env set AZURE_RESOURCE_GROUP rg-acatcp
azd env set AZURE_APP_A_VNET_NAME vnet-a
azd env set AZURE_APP_A_SUBNET_NAME snet-a
azd env set AZURE_APP_B_VNET_NAME vnet-b
azd env set AZURE_APP_B_SUBNET_NAME snet-b
```

| Environment variable | Bicep parameter | If not set |
| --- | --- | --- |
| `AZURE_LOCATION` | `location` | Region selected by `azd env new --location` or in the `azd up` prompt |
| `AZURE_ENV_NAME` | `environmentName` | azd environment name (set automatically by azd) |
| `AZURE_RESOURCE_GROUP` | `resourceGroupName` | `rg-acatcp-<suffix>` |
| `AZURE_APP_A_VNET_NAME` / `AZURE_APP_A_SUBNET_NAME` | `appAVnetName` / `appASubnetName` | `vnet-a-<suffix>` / `snet-a-<suffix>` |
| `AZURE_APP_B_VNET_NAME` / `AZURE_APP_B_SUBNET_NAME` | `appBVnetName` / `appBSubnetName` | `vnet-b-<suffix>` / `snet-b-<suffix>` |
| `AZURE_APP_B_PE_SUBNET_NAME` | `appBPrivateEndpointSubnetName` | `snet-pe-<suffix>` |
| `AZURE_NAME_SUFFIX` | `nameSuffix` | Six characters derived from the environment name, region and other values |
| `AZURE_CONTAINER_REGISTRY_NAME` | `containerRegistryName` | `acr<hash>` |
| `AZURE_APP_A_IMAGE` / `AZURE_APP_B_IMAGE` | `appAImage` / `appBImage` | Empty (`azd deploy` sets the ACR images, so these are normally not needed. If set, use `repository:tag` from ACR) |

For VNet address spaces (`appAVnetAddressPrefix` / `appBVnetAddressPrefix`) and tags, add entries to `infra/main.parameters.json` or change the Bicep defaults.

### azd notes

- **Do not change the region later**: You cannot change the region of an environment (VNet, etc.) after it is created. To change it, create a new azd environment (`azd env new`).
- **ACR cost**: Basic ACR is billed daily even when unused. Delete it with `azd down` when it is no longer needed.
- **Bootstrap image**: The first `azd provision` creates apps with the public Microsoft Container Registry image (`bootstrapImage`) because ACR does not yet contain the app images. It is no longer needed after `azd deploy` switches to the ACR images.
- **Building locally**: Set `remoteBuild` to `false` in `azure.yaml` (Docker or Podman is required). `azd package` builds the images, and `azd deploy` pushes them to ACR. azd builds with `linux/amd64`; on Apple Silicon Macs, emulation makes the build slower.
- **app-a endpoint display**: azd displays the deployed container app endpoint as `https://<FQDN>/`. Since app-a uses TCP ingress inside the VNet, it cannot be reached using that URL (this is only a display issue).
- **Recreate with the same name**: Without `--purge`, `azd down` soft-deletes the Log Analytics workspace, so you cannot recreate it with the same name for 14 days.

### Items to verify with azd in a live environment

- Run `azd up` / `azd deploy` / `azd down` (not done in this repository). ACR push and pull also need a live run to verify.
- First provisioning with the bootstrap image (see item 4 under [Items to verify in a live environment](#items-to-verify-in-a-live-environment)).
- Whether ACR Tasks are available. Some subscriptions do not allow ACR Tasks (`TasksOperationsNotAllowed`); in that case, azd switches to a local Docker build (Docker is required).
- Local build (`remoteBuild: false`). Docker / Podman is required and has not been tested in this repository.
- Propagation time for the `AcrPull` assignment. Since `azd deploy` runs right after `azd provision`, the pull may fail temporarily if the assignment has not propagated (rerun `azd deploy` if this happens).
- Whether reprovisioning (`SERVICE_*_RESOURCE_EXISTS` is `true`) keeps the deployed image. Only Bicep compilation and `validate` have been checked.
- TCP `18192` connectivity and name resolution through the Private Endpoint, listed under [Items to verify in a live environment](#items-to-verify-in-a-live-environment).
- Automatic deployment (see [Items to verify for automatic deployment in a live environment](#items-to-verify-for-automatic-deployment-in-a-live-environment)).

## Automatic deployment (GitHub Actions)

`.github/workflows/azure-dev.yml` runs tests, provisioning, image builds, pushes to ACR and deployments to Container Apps (which pull from ACR) automatically when changes are pushed to `main`. Builds run with ACR Tasks, so Docker is not needed on the runner. Azure sign-in uses OpenID Connect (federated credentials); no secrets are used.

| Trigger | What runs |
| --- | --- |
| Push to `main` that changes `app-a/`, `app-b/`, `infra/`, `azure.yaml`, `pom.xml` or the workflow itself | Tests → `azd provision` → `azd deploy` |
| Pull request to `main` that changes `app-a/`, `app-b/` or `pom.xml` | Tests only (does not access Azure) |
| Manual run (`workflow_dispatch`) | On `main`, deploys as for a push; on other branches, runs tests only |

The steps run in this order. Deployments run one at a time to avoid concurrent deployments to the same environment.

1. `test` job: runs `mvn -B -ntp verify` with Java 25 (Temurin).
2. `deploy` job (only after `test` succeeds, and only on `main`): loads repository variables (`AZURE_*`). It reports an error if required variables are missing.
3. Signs in to Azure with `azd auth login` using federated credentials.
4. `azd provision --no-prompt`: applies the contents of `infra/`. Existing apps keep their current (ACR) images.
5. `azd deploy --no-prompt`: builds with ACR Tasks, pushes to ACR and creates new revisions in Container Apps (the apps pull from ACR with the managed identity).

### Enable it (once)

After pushing this repository to GitHub, run the following locally:

```bash
gh auth login
azd auth login

# Create an azd environment if needed (to set names, run azd env set as described in "Set names and other values" first)
azd env new acatcp-dev --location japaneast

# Create the Azure identity (service principal and federated credentials) and GitHub repository variables
azd pipeline config
```

Based on the azd 1.37.3 source, `azd pipeline config` does the following (it has not been run):

- Registers an app (service principal) in Microsoft Entra ID and, by default, assigns the `Contributor` and `User Access Administrator` roles at subscription scope. `User Access Administrator` is needed for Bicep to create the `AcrPull` role assignment. To limit permissions, specify roles with `--principal-role`; permission to write role assignments is still required.
- Creates federated credentials for pull requests, the current branch and `main`.
- Sets GitHub repository variables (see the table below).
- Does not generate a workflow because `.github/workflows/azure-dev.yml` already exists.

Once a commit containing the workflow is on `main`, a push to `main` starts automatic deployment (`azd pipeline config` may offer to commit and push).

### Repository variables

`azd pipeline config` sets these as repository variables, not secrets (Settings → Secrets and variables → Actions → Variables).

| Variable | Description |
| --- | --- |
| `AZURE_CLIENT_ID` (required) | Client ID of the created service principal |
| `AZURE_TENANT_ID` (required) | Entra tenant ID |
| `AZURE_SUBSCRIPTION_ID` (required) | Target subscription |
| `AZURE_ENV_NAME` (required) | azd environment name |
| `AZURE_LOCATION` (required) | Region |
| `AZURE_RESOURCE_GROUP`, `AZURE_APP_A_VNET_NAME`, etc. (optional) | Environment variables from [Set names and other values](#set-names-and-other-values). They are set only when the azd environment has a value. Unset values use the defaults (names that look random) |

If any of the five required variables is missing, the workflow stops with an error asking you to run `azd pipeline config`. This happened when `main` was pushed with no variables set: the `test` job succeeded, and the `deploy` job stopped at `Load repository variables` because all five were missing (it did not connect to Azure; later steps were skipped). Until you run `azd pipeline config`, every push to `main` will have the same result.

The workflow passes only repository variables with values whose names start with `AZURE_` to azd. An empty value overrides the Bicep default instead of using it (`azd provision --preview` with an empty `AZURE_NAME_SUFFIX` failed because `nameSuffix` must have at least three characters). To change a name later, use `gh variable set <name> --body <value>`, but changing it after the first provision creates different resources.

### Notes (automatic deployment)

- **Only `main` deploys**: The `deploy` job runs only on `main`. To deploy from another branch, create federated credentials for that branch (run `azd pipeline config` on it) and change the workflow's `if:` condition.
- **Do not use this with Azure portal "Continuous Deployment"**: A GitHub Actions workflow generated by Container Apps "Continuous Deployment" updates the same app through a separate path. This repository does not include portal-generated workflows. If you disable the portal setting and delete its workflow, the repository secrets (`APPA_*`, `APPB_*`: client ID, tenant ID, subscription ID, registry username and password) remain. `azure-dev.yml` does not use these secrets, so delete them if they are no longer needed (`gh secret delete <name>`). As they include registry passwords, we recommend deleting them or regenerating the passwords.
- **ACR images accumulate**: Each deployment leaves a new image tag (`azd-deploy-<UNIX time>`) in ACR. Remove old images with `az acr repository delete` or a similar command.

### Items to verify for automatic deployment in a live environment

- Running `azd pipeline config`, signing in to azd from GitHub Actions with federated credentials, and running `azd provision` / `azd deploy` from the workflow have not been done in this repository. Checks so far include `actionlint`, a local test of the repository-variable loading step, and a GitHub run where the `test` job succeeded and the `deploy` job stopped because variables were missing.
- `azd pipeline config` is a beta feature in azd 1.37.3. It needs permission to register an app in Entra ID and assign roles in the subscription (for example, Owner).
- The service principal's default subscription roles (`Contributor` and `User Access Administrator`) are broad. Consider limiting the scope for production use.
- On the first deployment, the apps start with the bootstrap image because ACR has no app images yet (see item 4 under [Items to verify in a live environment](#items-to-verify-in-a-live-environment)). If `azd provision` fails, `azd deploy` does not run.
- Propagation time for the `AcrPull` assignment (see [Items to verify with azd in a live environment](#items-to-verify-with-azd-in-a-live-environment)).
- The action versions used by the workflow (`actions/checkout@v7`, `actions/setup-java@v6`, `Azure/setup-azd@v2`) are the latest major versions at the time of writing.

## Reference: deploy to one environment with Azure CLI

This is a guide to using `az containerapp` to put A and B in the same Container Apps environment, separate from the Bicep setup above. It is based on Microsoft Learn; **deployment to an ACA environment itself has not been tested in this repository** (container-to-container communication was tested locally with Podman).

### Key points

| Topic | Description |
| --- | --- |
| Image | A Linux `linux/amd64` image is required ([Containers in Azure Container Apps](https://learn.microsoft.com/azure/container-apps/containers#limitations)). On an Apple Silicon Mac, build with `--platform linux/amd64` or use an ACR task (`az acr build`) |
| App A ingress | Use `transport: tcp`. In addition to the main port (`targetPort`), set extra ports in `additionalPortMappings`. Up to five extra ports are supported per app; `36985` is reserved and cannot be used. The `containerapp` CLI extension is required (`az extension add -n containerapp`) ([Ingress overview](https://learn.microsoft.com/azure/container-apps/ingress-overview#additional-tcp-ports)) |
| Reaching app A | A TCP ingress app can be reached from other container apps in the same environment by using the app name and public port (`exposedPort`) |
| App B configuration | Set app A's app name in `TCP_SERVER_HOST` and use app A's `exposedPort` for the `port` query parameter |
| VNet | External TCP ingress is supported only in VNet-integrated environments. App A is called only by app B within the environment, so `external: false` is used. The portal guide also says "TCP ingress is available in VNet-configured environments", so a VNet-integrated environment is recommended. Confirm with a proof of concept whether internal-only TCP ingress works without a VNet-integrated environment |

If `exposedPort` differs from `targetPort`, the `{port}` in the `Hello {port}` message sent by B is the public port, while the port in A's reply is the container port (`targetPort`). The examples below use the same value for both.

### Build and push images

```bash
# Build on ACR (the default image platform is linux/amd64)
az acr build --registry <ACR name> --image aca-tcp-a:1.0 app-a
az acr build --registry <ACR name> --image aca-tcp-b:1.0 app-b

# To build locally (on Apple Silicon, amd64 emulation takes longer)
podman build --platform linux/amd64 -t <ACR name>.azurecr.io/aca-tcp-a:1.0 app-a
```

### App A (`app-a.yaml`)

```yaml
name: app-a
type: Microsoft.App/containerApps
location: japaneast # Same region as the environment
properties:
  managedEnvironmentId: <environment resource ID>
  configuration:
    activeRevisionsMode: Single
    # Add ACR image authentication settings (registries) for your environment
    ingress:
      external: false
      transport: tcp
      targetPort: 18192
      exposedPort: 18192
      additionalPortMappings:
      - targetPort: 28192
        exposedPort: 28192
        external: false
      - targetPort: 38192
        exposedPort: 38192
        external: false
  template:
    containers:
    - name: app-a
      image: <ACR name>.azurecr.io/aca-tcp-a:1.0
      resources:
        cpu: 0.5
        memory: 1Gi
    scale:
      minReplicas: 1
      maxReplicas: 1
```

```bash
az extension add -n containerapp
az containerapp create --name app-a --resource-group <resource group> --yaml app-a.yaml
```

### App B

Also set `--registry-server` and `--registry-identity` as required to pull images from ACR in your environment.

```bash
az containerapp create \
  --name app-b --resource-group <resource group> --environment <environment name> \
  --image <ACR name>.azurecr.io/aca-tcp-b:1.0 \
  --ingress external --transport http --target-port 8080 \
  --env-vars TCP_SERVER_HOST=app-a \
  --cpu 0.5 --memory 1Gi --min-replicas 1 --max-replicas 1
```

After deployment, call `https://<app B FQDN>/api/tcp?port=28192`. HTTP ingress terminates TLS before the request reaches B, so B itself uses plain HTTP on port `8080`.

## Verification status

| Check | Result |
| --- | --- |
| `mvn -B -ntp clean verify` (JDK 25.0.5 / Maven 3.10.0) | All tests passed |
| Local communication between JARs (all three ports on A) | Replies as expected |
| Error cases (400 / 404 / 405 / 502) | Expected status codes |
| Communication between containers on a Podman network (`TCP_SERVER_HOST=app-a`), and 504 when A is stopped | As expected |
| `podman inspect` | A exposes ports 18192 / 28192 / 38192; B exposes 8080 |
| Shutdown with `SIGTERM` | Exits normally with code 143 (`podman stop` takes about 0.4 seconds) |
| Build and start app A with `podman build --platform linux/amd64` | Build and startup both succeeded on Apple Silicon |
| `az bicep build` (`infra/main.bicep` and modules) and `az bicep build-params` (`main.sample.bicepparam`) | No errors or warnings |
| `az deployment sub validate` (`infra/main.bicep`: defaults; `appAExists` / `appBExists` set to `true`; ACR `repository:tag` values for `appAImage` / `appBImage`; and name overrides) | All four succeeded |
| `az deployment sub what-if` (defaults and name overrides) | No errors. All 17 planned resources are `Create`. Name overrides (resource group, VNets, subnets and ACR) are reflected. Both apps use the MCR bootstrap image; no `docker.io` appears in the output. ACR is Basic, with admin user and anonymous pull disabled |
| `azd provision --preview` (including name overrides) | No errors. All 10 planned resources, including ACR, are `Create`. Name overrides (resource group, VNets, ACR and `nameSuffix`) appear in the preview |
| `azd package --all` | Succeeded (no local build; ACR Tasks builds during `azd deploy`) |
| JSON schema validation for `azure.yaml` | No errors |
| Search for `docker.io` and `Docker Hub` in `infra/`, `azure.yaml`, `.github/` and Dockerfiles | No container app image references (only explanatory text and comments) |
| `actionlint` (`.github/workflows/azure-dev.yml`) | No errors |
| Run the workflow's "Load repository variables" step locally | Errors when required values are missing; exports only `AZURE_*` values that are set |
| `azd provision --preview` with an empty `AZURE_NAME_SUFFIX` | Error because `nameSuffix` must be at least three characters (evidence that an empty value must not be passed) |
| Live Bicep deployment and TCP connectivity through the Private Endpoint | Not done (see [Items to verify in a live environment](#items-to-verify-in-a-live-environment)) |
| `azd up` / `azd deploy` / `azd down`, ACR push and ACR pull | Not done (see [Items to verify with azd in a live environment](#items-to-verify-with-azd-in-a-live-environment)) |
| GitHub Actions run (push to `main`, repository variables unset) | The `test` job (Java 25, `mvn -B -ntp verify`) succeeded. The `deploy` job stopped at `Load repository variables` because the five required variables were missing (expected; no Azure connection was made and later steps were skipped) |
| `azd pipeline config`, `azd auth login` with federated credentials, and workflow `azd provision` / `azd deploy` | Not done (see [Items to verify for automatic deployment in a live environment](#items-to-verify-for-automatic-deployment-in-a-live-environment)) |
| Deployment to an ACA environment (`az containerapp`) | Not done |

## Notes

- **Reply format**: A's reply is `Acknowledged {message} This message is sent from {port}!` (`EchoServer.formatReply`; there is no full stop after the message). B sends `Hello 18192, how are you?`, which ends with `?`; adding a full stop after the message would produce `...how are you?. This message...`.
- **A newline is required**: A treats a newline (LF) as the end of a message. If the connection stays open without a newline, A does not reply and closes it after 60 seconds without activity (if the connection closes, the last line is treated as a message even without a newline). Some `nc` implementations keep the connection open after standard input ends, so the examples use `-w 2`.
- **Ports B can connect to**: B accepts any port from 1 to 65535 (the destination host is fixed by `TCP_SERVER_HOST`). If exposed to the internet, consider restricting it to A's ports (`18192` / `28192` / `38192`).
- **Encryption**: TCP between A and B is unencrypted.
- **Logs**: One event per line is written to standard output.

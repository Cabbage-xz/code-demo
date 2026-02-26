# Route Demo Module Documentation

## Overview
The route-demo module implements a dynamic routing system that enables switching between different database environments or data sources based on runtime context. This is particularly useful for applications that need to support multiple environments (e.g., beta vs production) or tenant-specific databases.

**Module Name**: route
**Type**: Spring Boot Application
**Purpose**: Dynamic database routing system

## Architecture
The module implements a sophisticated routing mechanism using several design patterns:

```
Controllers
├── FaultController           # Example controller demonstrating routing

Core Routing Components
├── BeanRouteFactory          # Central factory for managed routed beans
├── RouteCustom               # Annotation for routing configuration
├── RouteContext              # Thread-local context for routing
├── RouteConfig               # Routing configuration model
├── RouteConfigManager        # Manages routing configurations
└── RouteInterceptor          # Intercepts requests to set routing context

Data Access Layer
├── Entities                  # Different entities for different environments
│   ├── BetaFaultDetailEntity
│   ├── CommFaultDetailEntity
│   ├── BetaOtherAppFaultDetailEntity
│   └── CommOtherAppFaultDetailEntity
├── Mappers                   # Routed mapper implementations
│   ├── BaseFaultDetailRouterMapper
│   ├── BetaFaultDetailRouterMapper
│   ├── CommFaultDetailRouterMapper
│   ├── BetaOtherAppFaultDetailRouterMapper
│   └── CommOtherAppFaultDetailRouterMapper
└── Services                  # Routed service implementations
    ├── IBaseFaultDetailService
    ├── IBetaFaultDetailService
    ├── ICommFaultDetailService
    ├── IBetaOtherAppFaultDetailService
    └── ICommOtherAppFaultDetailService

Helper Components
├── WebConfig                 # Web configuration
├── RouteConfigProperties     # Configuration properties
└── GlobalExceptionHandler    # Global exception handling
```

## Key Features

### Dynamic Routing
- Annotation-driven routing configuration using `@RouteCustom`
- Runtime selection of different beans (mappers/services) based on context
- Thread-local context management for request-scoped routing

### Multi-Environment Support
- Separate implementations for beta and production environments
- Ability to route to different databases/data sources dynamically
- Consistent interface across different environments

### Factory Pattern Implementation
- Centralized `BeanRouteFactory` manages all routed beans
- Type-safe retrieval of appropriate bean based on routing context
- Support for both mapper-level and service-level routing

### Request Interception
- `RouteInterceptor` analyzes incoming requests to determine routing context
- Automatic setup of routing context based on request path or parameters

## Core Concepts

### RouteCustom Annotation
Used to mark beans that should participate in routing. Contains:
- `suffix`: The routing identifier (e.g., "/betaSuffix", "/commSuffix")
- `moduleName`: Logical name for the module being routed

### RouteContext
Thread-local storage that maintains routing information during request processing:
- `moduleName`: The logical module name
- `routeSuffix`: The specific routing identifier

### Bean Registration Process
1. At startup, the `BeanRouteFactory` scans for `@RouteCustom` annotated beans
2. Builds internal routing maps for both mappers and services
3. Makes routed beans available via factory methods

## API Endpoints
The module includes example endpoints demonstrating routing:
- `/fault/distribution/beta_fault` - Routes to beta environment for fault distribution
- `/fault/distribution/comm_fault` - Routes to comm environment for fault distribution
- `/fault/detail/beta_other_app` - Routes to beta environment for other app details
- `/fault/detail/comm_other_app` - Routes to comm environment for other app details
- Additional count endpoints for various scenarios

## Technology Stack
- **Framework**: Spring Boot 3.5.6
- **ORM**: MyBatis-Plus 3.5.6
- **Database**: MySQL
- **Utilities**: Hutool-all 5.8.27
- **Pattern**: Factory pattern, Context pattern, Annotation processing

## Dependencies
- Spring Boot Web
- MyBatis-Plus Spring Boot 3 Starter
- MySQL Connector/J
- Lombok
- Hutool All utilities

## Usage
1. Annotate beans with `@RouteCustom` specifying the routing suffix and module name
2. Use `BeanRouteFactory` to retrieve appropriate beans based on current context
3. Implement interceptors or controllers to set routing context as needed
4. The system automatically routes to the correct implementation based on context

## Security Considerations
- Route interceptor validates routing parameters to prevent invalid routing
- Proper exception handling to avoid exposing internal routing details
- Input validation on routing identifiers
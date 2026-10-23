# Пример фичи Orchestrator: изменение категории

Иллюстрация структуры, не готовый компилируемый модуль. CurrentUser/CompanyAccess/DTO и пути адаптировать к реальной реализации.

```java
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/payments")
public interface PaymentController {
    @PatchMapping("/{paymentId}/category")
    @PreAuthorize("isAuthenticated()")
    PaymentRs updateCategory(@AuthenticationPrincipal CurrentUser user,
        @PathVariable UUID companyId, @PathVariable UUID paymentId,
        @RequestBody @Valid UpdateCategoryRq request);
}

@RestController
@RequiredArgsConstructor
class PaymentControllerImpl implements PaymentController {
    private final CompanyAccess companyAccess;
    private final PaymentApplicationService service;
    private final PaymentMapper mapper;

    public PaymentRs updateCategory(CurrentUser user, UUID companyId,
                                   UUID paymentId, UpdateCategoryRq request) {
        var scope = companyAccess.requireWrite(user.id(), companyId);
        return mapper.convert(service.updateCategory(scope, paymentId, request.category()));
    }
}

public record UpdateCategoryRq(@NotBlank @Size(max = 64) String category) {}

@Service
@RequiredArgsConstructor
class PaymentApplicationService {
    private final PaymentRepository repository;

    @Transactional
    public Payment updateCategory(CompanyScope scope, UUID paymentId, String category) {
        var payment = repository.find(scope, paymentId).orElseThrow(NotFoundException::new);
        payment.changeCategory(category, CategorySource.USER);
        return repository.save(scope, payment);
    }
}
```

PreProcessor определяет исходную категорию. Orchestrator сохраняет и позволяет исправить её. Фильтр уже создал principal из доверенного контекста; JwtDecoder не нужен.

Тест Orchestrator передаёт X-User-Id/канонический контекст доверенного вызывающего. Проверить разрешённую правку, 401 без контекста, 403 без роли, 404 чужого платежа, отсутствие записи в чужую организацию. Gateway-тест отдельно доказывает, что клиентские X-User-* заменяются; сетевой тест — что нельзя обратиться к сервису извне.

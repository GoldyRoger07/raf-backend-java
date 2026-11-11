package com.raikiservice.raf.wallet.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.raikiservice.raf.auth.service.DeviceLinkService;
import com.raikiservice.raf.wallet.domain.WithdrawalRequest;
import com.raikiservice.raf.wallet.web.dto.AdminWithdrawalResponse;
import com.raikiservice.raf.wallet.web.dto.AdminWithdrawalResponse.OtherAccount;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alertes de multi-compte pour la file des retraits : comptes lies par appareil, et
 * numero de reception deja associe a un autre compte (son telephone, ou la
 * destination d'un de ses retraits). Deux requetes par page, quelle que soit sa taille.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalFraudSignals {

    private final DeviceLinkService deviceLinkService;
    private final PayoutSpecialCaseService payoutSpecialCaseService;
    private final NamedParameterJdbcTemplate named;

    @Transactional(propagation = Propagation.MANDATORY)
    public Page<AdminWithdrawalResponse> annotate(Page<WithdrawalRequest> page) {
        List<WithdrawalRequest> rows = page.getContent();
        Set<Long> userIds = rows.stream().map(r -> r.getUser().getId()).collect(Collectors.toSet());
        Set<String> numbers = rows.stream()
                .map(WithdrawalRequest::getDestinationNumber)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Integer> linked = deviceLinkService.linkedCounts(userIds);
        Map<String, Map<Long, String>> owners = owners(numbers);
        Set<Long> special = payoutSpecialCaseService.among(userIds);

        return page.map(request -> {
            Long owner = request.getUser().getId();
            List<OtherAccount> others = new ArrayList<>();
            String number = request.getDestinationNumber();
            if (number != null) {
                owners.getOrDefault(number, Map.of()).forEach((id, name) -> {
                    if (!id.equals(owner)) {
                        others.add(new OtherAccount(id, name));
                    }
                });
            }
            return AdminWithdrawalResponse.from(request, linked.getOrDefault(owner, 0), others,
                    special.contains(owner));
        });
    }

    /** Pour chaque numero : les comptes qui l'ont pour telephone ou l'ont deja indique pour un retrait. */
    private Map<String, Map<Long, String>> owners(Set<String> numbers) {
        Map<String, Map<Long, String>> owners = new HashMap<>();
        if (numbers.isEmpty()) {
            return owners;
        }
        named.query(
                "SELECT x.number, u.id, u.first_name, u.last_name FROM ("
                        + " SELECT phone AS number, id AS user_id FROM users WHERE phone IN (:numbers)"
                        + " UNION"
                        + " SELECT destination_number, user_id FROM withdrawal_requests"
                        + " WHERE destination_number IN (:numbers)"
                        + ") x JOIN users u ON u.id = x.user_id ORDER BY u.id",
                new MapSqlParameterSource("numbers", numbers),
                rs -> {
                    owners.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                            .put(rs.getLong(2), rs.getString(3) + " " + rs.getString(4));
                });
        return owners;
    }
}

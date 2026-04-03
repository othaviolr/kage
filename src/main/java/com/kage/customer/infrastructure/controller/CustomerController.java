package com.kage.customer.infrastructure.controller;

import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycOutput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.customer.application.usecase.blockcustomer.BlockCustomerInput;
import com.kage.customer.application.usecase.blockcustomer.BlockCustomerOutput;
import com.kage.customer.application.usecase.blockcustomer.BlockCustomerUseCase;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerInput;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerOutput;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerUseCase;
import com.kage.customer.application.usecase.getcustomer.GetCustomerInput;
import com.kage.customer.application.usecase.getcustomer.GetCustomerOutput;
import com.kage.customer.application.usecase.getcustomer.GetCustomerUseCase;
import com.kage.customer.application.usecase.rejectkyc.RejectKycInput;
import com.kage.customer.application.usecase.rejectkyc.RejectKycOutput;
import com.kage.customer.application.usecase.rejectkyc.RejectKycUseCase;
import com.kage.customer.application.usecase.updateaddress.UpdateAddressInput;
import com.kage.customer.application.usecase.updateaddress.UpdateAddressOutput;
import com.kage.customer.application.usecase.updateaddress.UpdateAddressUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CreateCustomerUseCase createCustomerUseCase;
    private final GetCustomerUseCase getCustomerUseCase;
    private final BlockCustomerUseCase blockCustomerUseCase;
    private final ApproveKycUseCase approveKycUseCase;
    private final RejectKycUseCase rejectKycUseCase;
    private final UpdateAddressUseCase updateAddressUseCase;

    public CustomerController(CreateCustomerUseCase createCustomerUseCase,
                              GetCustomerUseCase getCustomerUseCase,
                              BlockCustomerUseCase blockCustomerUseCase,
                              ApproveKycUseCase approveKycUseCase,
                              RejectKycUseCase rejectKycUseCase,
                              UpdateAddressUseCase updateAddressUseCase) {
        this.createCustomerUseCase = createCustomerUseCase;
        this.getCustomerUseCase = getCustomerUseCase;
        this.blockCustomerUseCase = blockCustomerUseCase;
        this.approveKycUseCase = approveKycUseCase;
        this.rejectKycUseCase = rejectKycUseCase;
        this.updateAddressUseCase = updateAddressUseCase;
    }

    @PostMapping
    public ResponseEntity<CreateCustomerOutput> create(@RequestBody CreateCustomerInput input) {
        var output = createCustomerUseCase.execute(input);
        return ResponseEntity.status(HttpStatus.CREATED).body(output);
    }

    @GetMapping("/{id}")
    public ResponseEntity<GetCustomerOutput> getById(@PathVariable UUID id) {
        var output = getCustomerUseCase.execute(new GetCustomerInput(id));
        return ResponseEntity.ok(output);
    }

    @PatchMapping("/{id}/block")
    public ResponseEntity<BlockCustomerOutput> block(@PathVariable UUID id) {
        var output = blockCustomerUseCase.execute(new BlockCustomerInput(id));
        return ResponseEntity.ok(output);
    }

    @PatchMapping("/{id}/kyc/approve")
    public ResponseEntity<ApproveKycOutput> approveKyc(@PathVariable UUID id) {
        var output = approveKycUseCase.execute(new ApproveKycInput(id));
        return ResponseEntity.ok(output);
    }

    @PatchMapping("/{id}/kyc/reject")
    public ResponseEntity<RejectKycOutput> rejectKyc(@PathVariable UUID id) {
        var output = rejectKycUseCase.execute(new RejectKycInput(id));
        return ResponseEntity.ok(output);
    }

    @PatchMapping("/{id}/address")
    public ResponseEntity<UpdateAddressOutput> updateAddress(@PathVariable UUID id,
                                                             @RequestBody UpdateAddressInput input) {
        var output = updateAddressUseCase.execute(input);
        return ResponseEntity.ok(output);
    }
}
package com.kage.customer.domain.entity;

import com.kage.customer.domain.enums.CustomerStatus;
import com.kage.customer.domain.enums.KycStatus;
import com.kage.customer.domain.valueobject.Address;
import com.kage.customer.domain.valueobject.PersonalInfo;
import com.kage.shared.domain.exception.DomainException;

import java.time.LocalDateTime;
import java.util.UUID;

public class Customer {

    private final UUID id;
    private PersonalInfo personalInfo;
    private Address address;
    private KycStatus kycStatus;
    private CustomerStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Customer(UUID id, PersonalInfo personalInfo, Address address) {
        this.id = id;
        this.personalInfo = personalInfo;
        this.address = address;
        this.kycStatus = KycStatus.PENDING;
        this.status = CustomerStatus.INACTIVE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public static Customer create(PersonalInfo personalInfo, Address address) {
        return new Customer(UUID.randomUUID(), personalInfo, address);
    }

    public void approveKyc() {
        if (this.kycStatus == KycStatus.APPROVED) {
            throw new DomainException("KYC já foi aprovado");
        }
        this.kycStatus = KycStatus.APPROVED;
        this.status = CustomerStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void rejectKyc() {
        if (this.kycStatus == KycStatus.APPROVED) {
            throw new DomainException("Não é possível rejeitar um KYC já aprovado");
        }
        this.kycStatus = KycStatus.REJECTED;
        this.updatedAt = LocalDateTime.now();
    }

    public void block() {
        if (this.status == CustomerStatus.BLOCKED) {
            throw new DomainException("Cliente já está bloqueado");
        }
        this.status = CustomerStatus.BLOCKED;
        this.updatedAt = LocalDateTime.now();
    }

    public void unblock() {
        if (this.status != CustomerStatus.BLOCKED) {
            throw new DomainException("Cliente não está bloqueado");
        }
        this.status = CustomerStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateAddress(Address newAddress) {
        if (newAddress == null) throw new DomainException("Endereço não pode ser nulo");
        this.address = newAddress;
        this.updatedAt = LocalDateTime.now();
    }

    public void updatePersonalInfo(PersonalInfo newInfo) {
        if (newInfo == null) throw new DomainException("Informações pessoais não podem ser nulas");
        this.personalInfo = newInfo;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return this.status == CustomerStatus.ACTIVE;
    }

    public boolean isKycApproved() {
        return this.kycStatus == KycStatus.APPROVED;
    }

    public UUID getId() { return id; }
    public PersonalInfo getPersonalInfo() { return personalInfo; }
    public Address getAddress() { return address; }
    public KycStatus getKycStatus() { return kycStatus; }
    public CustomerStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
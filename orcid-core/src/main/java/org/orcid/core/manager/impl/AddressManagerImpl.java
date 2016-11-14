/**
 * =============================================================================
 *
 * ORCID (R) Open Source
 * http://orcid.org
 *
 * Copyright (c) 2012-2014 ORCID, Inc.
 * Licensed under an MIT-Style License (MIT)
 * http://orcid.org/open-source-license
 *
 * This copyright and license information (including a link to the full license)
 * shall be included in its entirety in all copies or substantial portion of
 * the software.
 *
 * =============================================================================
 */
package org.orcid.core.manager.impl;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;
import javax.transaction.Transactional;

import org.orcid.core.exception.ApplicationException;
import org.orcid.core.exception.OrcidDuplicatedElementException;
import org.orcid.core.manager.AddressManager;
import org.orcid.core.manager.OrcidSecurityManager;
import org.orcid.core.manager.ProfileEntityCacheManager;
import org.orcid.core.manager.SourceManager;
import org.orcid.core.manager.read_only.impl.AddressManagerReadOnlyImpl;
import org.orcid.core.manager.validator.PersonValidator;
import org.orcid.core.utils.DisplayIndexCalculatorHelper;
import org.orcid.jaxb.model.common_rc3.Visibility;
import org.orcid.jaxb.model.record_rc3.Address;
import org.orcid.jaxb.model.record_rc3.Addresses;
import org.orcid.persistence.jpa.entities.AddressEntity;
import org.orcid.persistence.jpa.entities.ProfileEntity;
import org.orcid.persistence.jpa.entities.SourceEntity;
import org.orcid.pojo.ajaxForm.PojoUtil;

public class AddressManagerImpl extends AddressManagerReadOnlyImpl implements AddressManager {

    @Resource
    protected OrcidSecurityManager orcidSecurityManager;
    
    @Resource
    protected SourceManager sourceManager;    
    
    @Resource
    private ProfileEntityCacheManager profileEntityCacheManager; 
    
    @Override
    @Transactional
    public Address updateAddress(String orcid, Long putCode, Address address, boolean isApiRequest) {
        SourceEntity sourceEntity = sourceManager.retrieveSourceEntity();
        AddressEntity updatedEntity = addressDao.getAddress(orcid, putCode);
        Visibility originalVisibility = Visibility.fromValue(updatedEntity.getVisibility().value());
        
        //Save the original source
        String existingSourceId = updatedEntity.getSourceId();
        String existingClientSourceId = updatedEntity.getClientSourceId();
        
        //If it is an update from the API, check the source and preserve the original visibility
        if(isApiRequest) {
            orcidSecurityManager.checkSource(updatedEntity);            
        }
        
        // Validate the address
        PersonValidator.validateAddress(address, sourceEntity, false, isApiRequest, originalVisibility);
        // Validate it is not duplicated
        List<AddressEntity> existingAddresses = addressDao.getAddresses(orcid, getLastModified(orcid));
        for (AddressEntity existing : existingAddresses) {
            //If it is not the same element
            if(!existing.getId().equals(address.getPutCode())) {
                if (isDuplicated(existing, address, sourceEntity)) {
                    Map<String, String> params = new HashMap<String, String>();
                    params.put("type", "address");
                    params.put("value", address.getCountry().getValue().value());
                    throw new OrcidDuplicatedElementException(params);
                }
            }
        }
                        
        adapter.toAddressEntity(address, updatedEntity);
        updatedEntity.setLastModified(new Date());        

        //Be sure it doesn't overwrite the source
        updatedEntity.setSourceId(existingSourceId);
        updatedEntity.setClientSourceId(existingClientSourceId);                
        
        addressDao.merge(updatedEntity);
        return adapter.toAddress(updatedEntity);
    }
    
    @Override    
    public Address createAddress(String orcid, Address address, boolean isApiRequest) { 
        SourceEntity sourceEntity = sourceManager.retrieveSourceEntity();
        // Validate the address
        PersonValidator.validateAddress(address, sourceEntity, true, isApiRequest, null);
        // Validate it is not duplicated
        List<AddressEntity> existingAddresses = addressDao.getAddresses(orcid, getLastModified(orcid));
        for (AddressEntity existing : existingAddresses) {
            if (isDuplicated(existing, address, sourceEntity)) {
                Map<String, String> params = new HashMap<String, String>();
                params.put("type", "address");
                params.put("value", address.getCountry().getValue().value());
                throw new OrcidDuplicatedElementException(params);
            }            
        }

        AddressEntity newEntity = adapter.toAddressEntity(address);
        ProfileEntity profile = profileEntityCacheManager.retrieve(orcid);
        newEntity.setUser(profile);
        newEntity.setDateCreated(new Date());
        
        //Set the source
        if(sourceEntity.getSourceProfile() != null) {
            newEntity.setSourceId(sourceEntity.getSourceProfile().getId());
        }
        if(sourceEntity.getSourceClient() != null) {
            newEntity.setClientSourceId(sourceEntity.getSourceClient().getId());
        }        
        
        DisplayIndexCalculatorHelper.setDisplayIndexOnNewEntity(newEntity, isApiRequest);
        setIncomingPrivacy(newEntity, profile);
        addressDao.persist(newEntity);
        return adapter.toAddress(newEntity);
    }

    @Override
    @Transactional
    public boolean deleteAddress(String orcid, Long putCode) {
        AddressEntity entity = addressDao.getAddress(orcid, putCode);
        orcidSecurityManager.checkSource(entity);

        try {
            addressDao.remove(entity);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private boolean isDuplicated(AddressEntity existing, Address address, SourceEntity source) {
        if (!existing.getId().equals(address.getPutCode())) {
            //If they have the same source 
            String existingSourceId = existing.getElementSourceId(); 
            if (!PojoUtil.isEmpty(existingSourceId) && existingSourceId.equals(source.getSourceId())) {
                if(existing.getIso2Country().equals(address.getCountry().getValue())) {
                    return true;
                }
            }
        }
        return false;
    }    
    
    private void setIncomingPrivacy(AddressEntity entity, ProfileEntity profile) {
        org.orcid.jaxb.model.common_rc3.Visibility incomingCountryVisibility = entity.getVisibility();
        org.orcid.jaxb.model.common_rc3.Visibility defaultCountryVisibility = (profile.getActivitiesVisibilityDefault() == null) ? org.orcid.jaxb.model.common_rc3.Visibility.PRIVATE : org.orcid.jaxb.model.common_rc3.Visibility.fromValue(profile.getActivitiesVisibilityDefault().value());        
        if (profile.getClaimed() != null && profile.getClaimed()) {
            entity.setVisibility(defaultCountryVisibility);            
        } else if (incomingCountryVisibility == null) {
            entity.setVisibility(org.orcid.jaxb.model.common_rc3.Visibility.PRIVATE);
        }
    }    
    
    @Override
    public Addresses updateAddresses(String orcid, Addresses addresses) {
        List<AddressEntity> existingAddressList = addressDao.getAddresses(orcid, getLastModified(orcid));
        //Delete the deleted ones
        for(AddressEntity existingAddress : existingAddressList) {
            boolean deleteMe = true;            
            if(addresses.getAddress() != null) {
                for(Address updatedOrNew : addresses.getAddress()) {
                    if(existingAddress.getId().equals(updatedOrNew.getPutCode())) {
                        deleteMe = false;
                        break;
                    }
                }
            }
            if(deleteMe) {
                try {
                    addressDao.deleteAddress(orcid, existingAddress.getId());
                } catch (Exception e) {
                    throw new ApplicationException("Unable to delete address " + existingAddress.getId(), e);
                }
            }
        }
        
        if(addresses != null && addresses.getAddress() != null) {
            for(Address updatedOrNew : addresses.getAddress()) {
                if(updatedOrNew.getPutCode() != null) {
                    //Update the existing ones
                   for(AddressEntity existingAddress : existingAddressList) {
                       if(existingAddress.getId().equals(updatedOrNew.getPutCode())) {
                           existingAddress.setLastModified(new Date());
                           existingAddress.setVisibility(updatedOrNew.getVisibility());
                           existingAddress.setIso2Country(updatedOrNew.getCountry().getValue());
                           existingAddress.setDisplayIndex(updatedOrNew.getDisplayIndex());
                           addressDao.merge(existingAddress);
                       }
                   }
                } else {
                    //Add the new ones
                    AddressEntity newAddress = adapter.toAddressEntity(updatedOrNew);
                    SourceEntity sourceEntity = sourceManager.retrieveSourceEntity();
                    ProfileEntity profile = new ProfileEntity(orcid);
                    newAddress.setUser(profile);
                    newAddress.setDateCreated(new Date());
                    
                    //Set the source id
                    if(sourceEntity.getSourceProfile() != null) {
                        newAddress.setSourceId(sourceEntity.getSourceProfile().getId());
                    }
                    if(sourceEntity.getSourceClient() != null) {
                        newAddress.setClientSourceId(sourceEntity.getSourceClient().getId());
                    }
                                                            
                    newAddress.setVisibility(updatedOrNew.getVisibility());
                    newAddress.setDisplayIndex(updatedOrNew.getDisplayIndex());
                    addressDao.persist(newAddress);
                    
                }
            }
        }        
        return addresses;
    }
}
